/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.webmonitor.history;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HistoryServerOptions;
import org.apache.flink.configuration.IllegalConfigurationException;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.runtime.history.FsJobArchivist;
import org.apache.flink.runtime.messages.webmonitor.JobDetails;
import org.apache.flink.runtime.messages.webmonitor.MultipleJobsDetails;
import org.apache.flink.runtime.rest.messages.DashboardConfiguration;
import org.apache.flink.runtime.rest.messages.DashboardConfigurationHeaders;
import org.apache.flink.runtime.rest.messages.JobsOverviewHeaders;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.runtime.webmonitor.testutils.HttpUtils;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.jackson.JacksonMapperFactory;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonFactory;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonGenerator;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rocksdb.RocksDBException;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for the HistoryServer. */
class HistoryServerTest {

    private static final JsonFactory JACKSON_FACTORY =
            new JsonFactory()
                    .enable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
    private static final ObjectMapper OBJECT_MAPPER =
            JacksonMapperFactory.createObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    private MiniClusterWithClientResource cluster;
    private File jmDirectory;
    private File hsDirectory;

    @BeforeEach
    void setUp(@TempDir File jmDirectory, @TempDir File hsDirectory) throws Exception {
        this.jmDirectory = jmDirectory;
        this.hsDirectory = hsDirectory;

        Configuration clusterConfig = new Configuration();
        clusterConfig.set(JobManagerOptions.ARCHIVE_DIR, jmDirectory.toURI().toString());

        cluster =
                new MiniClusterWithClientResource(
                        new MiniClusterResourceConfiguration.Builder()
                                .setConfiguration(clusterConfig)
                                .setNumberTaskManagers(1)
                                .setNumberSlotsPerTaskManager(2)
                                .build());
        cluster.before();
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.after();
        }
    }

    @ParameterizedTest(name = "Flink version less than 1.4: {0}")
    @ValueSource(booleans = {true, false})
    void testHistoryServerIntegration(final boolean versionLessThan14) throws Exception {
        final int numJobs = 2;
        final int numLegacyJobs = 1;

        CountDownLatch numExpectedArchivedJobs = new CountDownLatch(numJobs + numLegacyJobs);

        Configuration historyServerConfig = createTestConfiguration(false);

        HistoryServer hs =
                new HistoryServer(
                        historyServerConfig,
                        (event) -> {
                            if (event.getType()
                                    == HistoryServerArchiveFetcher.ArchiveEventType.CREATED) {
                                numExpectedArchivedJobs.countDown();
                            }
                        });

        try {
            hs.start();
            String baseUrl = "http://localhost:" + hs.getWebPort();

            assertThat(getJobsOverview(baseUrl).getJobs()).isEmpty();

            for (int x = 0; x < numJobs; x++) {
                runJob();
            }
            createLegacyArchive(jmDirectory.toPath(), versionLessThan14);
            waitForArchivesCreation(numJobs + numLegacyJobs);

            assertThat(numExpectedArchivedJobs.await(10L, TimeUnit.SECONDS)).isTrue();
            assertThat(getJobsOverview(baseUrl).getJobs()).hasSize(numJobs + numLegacyJobs);

            // checks whether the dashboard configuration contains all expected fields
            getDashboardConfiguration(baseUrl);
        } finally {
            hs.stop();
        }
    }

    @ParameterizedTest(name = "Flink version less than 1.4: {0}")
    @ValueSource(booleans = {true, false})
    void testRemoveOldestModifiedArchivesBeyondHistorySizeLimit(final boolean versionLessThan14)
            throws Exception {
        final int numArchivesToKeepInHistory = 2;
        final int numArchivesBeforeHsStarted = 4;
        final int numArchivesAfterHsStarted = 2;
        final int numArchivesToRemoveUponHsStart =
                numArchivesBeforeHsStarted - numArchivesToKeepInHistory;
        final long oneMinuteSinceEpoch = 1000L * 60L;
        List<String> expectedJobIdsToKeep = new LinkedList<>();

        for (int j = 0; j < numArchivesBeforeHsStarted; j++) {
            String jobId =
                    createLegacyArchive(
                            jmDirectory.toPath(), j * oneMinuteSinceEpoch, versionLessThan14);
            if (j >= numArchivesToRemoveUponHsStart) {
                expectedJobIdsToKeep.add(jobId);
            }
        }

        CountDownLatch numArchivesCreatedInitially = new CountDownLatch(numArchivesToKeepInHistory);
        CountDownLatch numArchivesDeletedInitially =
                new CountDownLatch(numArchivesToRemoveUponHsStart);
        CountDownLatch numArchivesCreatedTotal =
                new CountDownLatch(
                        numArchivesBeforeHsStarted
                                - numArchivesToRemoveUponHsStart
                                + numArchivesAfterHsStarted);
        CountDownLatch numArchivesDeletedTotal =
                new CountDownLatch(numArchivesToRemoveUponHsStart + numArchivesAfterHsStarted);

        Configuration historyServerConfig =
                createTestConfiguration(
                        HistoryServerOptions.HISTORY_SERVER_CLEANUP_EXPIRED_JOBS.defaultValue());
        historyServerConfig.set(
                HistoryServerOptions.HISTORY_SERVER_RETAINED_JOBS, numArchivesToKeepInHistory);
        HistoryServer hs =
                new HistoryServer(
                        historyServerConfig,
                        (event) -> {
                            switch (event.getType()) {
                                case CREATED:
                                    numArchivesCreatedInitially.countDown();
                                    numArchivesCreatedTotal.countDown();
                                    break;
                                case DELETED:
                                    numArchivesDeletedInitially.countDown();
                                    numArchivesDeletedTotal.countDown();
                                    break;
                            }
                        });

        try {
            hs.start();
            String baseUrl = "http://localhost:" + hs.getWebPort();
            assertThat(numArchivesCreatedInitially.await(10L, TimeUnit.SECONDS)).isTrue();
            assertThat(numArchivesDeletedInitially.await(10L, TimeUnit.SECONDS)).isTrue();
            assertThat(getIdsFromJobOverview(baseUrl))
                    .isEqualTo(new HashSet<>(expectedJobIdsToKeep));

            for (int j = numArchivesBeforeHsStarted;
                    j < numArchivesBeforeHsStarted + numArchivesAfterHsStarted;
                    j++) {
                expectedJobIdsToKeep.remove(0);
                expectedJobIdsToKeep.add(
                        createLegacyArchive(
                                jmDirectory.toPath(), j * oneMinuteSinceEpoch, versionLessThan14));
            }
            assertThat(numArchivesCreatedTotal.await(10L, TimeUnit.SECONDS)).isTrue();
            assertThat(numArchivesDeletedTotal.await(10L, TimeUnit.SECONDS)).isTrue();
            assertThat(getIdsFromJobOverview(baseUrl))
                    .isEqualTo(new HashSet<>(expectedJobIdsToKeep));
        } finally {
            hs.stop();
        }
    }

    private Set<String> getIdsFromJobOverview(String baseUrl) throws Exception {
        return getJobsOverview(baseUrl).getJobs().stream()
                .map(JobDetails::getJobId)
                .map(JobID::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void testFailIfHistorySizeLimitIsZero() throws Exception {
        assertThatThrownBy(() -> startHistoryServerWithSizeLimit(0))
                .isInstanceOf(IllegalConfigurationException.class);
    }

    @Test
    void testFailIfHistorySizeLimitIsLessThanMinusOne() throws Exception {
        assertThatThrownBy(() -> startHistoryServerWithSizeLimit(-2))
                .isInstanceOf(IllegalConfigurationException.class);
    }

    private void startHistoryServerWithSizeLimit(int maxHistorySize)
            throws IOException, FlinkException, InterruptedException, RocksDBException {
        Configuration historyServerConfig =
                createTestConfiguration(
                        HistoryServerOptions.HISTORY_SERVER_CLEANUP_EXPIRED_JOBS.defaultValue());
        historyServerConfig.set(HistoryServerOptions.HISTORY_SERVER_RETAINED_JOBS, maxHistorySize);
        new HistoryServer(historyServerConfig).start();
    }

    @Test
    void testCleanExpiredJob() throws Exception {
        runArchiveExpirationTest(true);
    }

    @Test
    void testRemainExpiredJob() throws Exception {
        runArchiveExpirationTest(false);
    }

    private void runArchiveExpirationTest(boolean cleanupExpiredJobs) throws Exception {
        int numExpiredJobs = cleanupExpiredJobs ? 1 : 0;
        int numJobs = 3;
        for (int x = 0; x < numJobs; x++) {
            runJob();
        }
        waitForArchivesCreation(numJobs);

        CountDownLatch numExpectedArchivedJobs = new CountDownLatch(numJobs);
        CountDownLatch firstArchiveExpiredLatch = new CountDownLatch(numExpiredJobs);
        CountDownLatch allArchivesExpiredLatch =
                new CountDownLatch(cleanupExpiredJobs ? numJobs : 0);

        Configuration historyServerConfig = createTestConfiguration(cleanupExpiredJobs);

        HistoryServer hs =
                new HistoryServer(
                        historyServerConfig,
                        (event) -> {
                            switch (event.getType()) {
                                case CREATED:
                                    numExpectedArchivedJobs.countDown();
                                    break;
                                case DELETED:
                                    firstArchiveExpiredLatch.countDown();
                                    allArchivesExpiredLatch.countDown();
                                    break;
                            }
                        });

        try {
            hs.start();
            String baseUrl = "http://localhost:" + hs.getWebPort();
            assertThat(numExpectedArchivedJobs.await(10L, TimeUnit.SECONDS)).isTrue();

            Collection<JobDetails> jobs = getJobsOverview(baseUrl).getJobs();
            assertThat(jobs).hasSize(numJobs);

            String jobIdToDelete =
                    jobs.stream()
                            .findFirst()
                            .map(JobDetails::getJobId)
                            .map(JobID::toString)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Expected at least one existing job"));

            // trigger another fetch and delete one archive from jm
            // we fetch again to probabilistically cause a concurrent deletion
            hs.fetchArchives();
            Files.deleteIfExists(jmDirectory.toPath().resolve(jobIdToDelete));

            assertThat(firstArchiveExpiredLatch.await(10L, TimeUnit.SECONDS)).isTrue();

            // check that archive is still/no longer present in hs
            Collection<JobDetails> jobsAfterDeletion = getJobsOverview(baseUrl).getJobs();
            assertThat(jobsAfterDeletion).hasSize(numJobs - numExpiredJobs);
            assertThat(
                            jobsAfterDeletion.stream()
                                    .map(JobDetails::getJobId)
                                    .map(JobID::toString)
                                    .filter(jobId -> jobId.equals(jobIdToDelete))
                                    .count())
                    .isEqualTo(1 - numExpiredJobs);

            // delete remaining archives from jm and ensure files are cleaned up
            List<String> remainingJobIds =
                    jobsAfterDeletion.stream()
                            .map(JobDetails::getJobId)
                            .map(JobID::toString)
                            .collect(Collectors.toList());

            for (String remainingJobId : remainingJobIds) {
                Files.deleteIfExists(jmDirectory.toPath().resolve(remainingJobId));
            }

            assertThat(allArchivesExpiredLatch.await(10L, TimeUnit.SECONDS)).isTrue();

            assertJobFilesCleanedUp(cleanupExpiredJobs);
        } finally {
            hs.stop();
        }
    }

    private void assertJobFilesCleanedUp(boolean jobFilesShouldBeDeleted) throws IOException {
        try (Stream<Path> paths = Files.walk(hsDirectory.toPath())) {
            final List<Path> jobFiles =
                    paths.filter(path -> !path.equals(hsDirectory.toPath()))
                            .map(path -> hsDirectory.toPath().relativize(path))
                            .filter(path -> !path.equals(Paths.get("config.json")))
                            .filter(path -> !path.equals(Paths.get("jobs")))
                            .filter(path -> !path.equals(Paths.get("jobs", "overview.json")))
                            .filter(path -> !path.equals(Paths.get("overviews")))
                            .collect(Collectors.toList());

            if (jobFilesShouldBeDeleted) {
                assertThat(jobFiles).isEmpty();
            } else {
                assertThat(jobFiles).isNotEmpty();
            }
        }
    }

    private void waitForArchivesCreation(int numJobs) throws InterruptedException {
        // the job is archived asynchronously after env.execute() returns
        File[] archives = jmDirectory.listFiles();
        while (archives == null || archives.length != numJobs) {
            Thread.sleep(50);
            archives = jmDirectory.listFiles();
        }
    }

    private Configuration createTestConfiguration(boolean cleanupExpiredJobs) {
        Configuration historyServerConfig = new Configuration();
        historyServerConfig.set(
                HistoryServerOptions.HISTORY_SERVER_ARCHIVE_DIRS, jmDirectory.toURI().toString());
        historyServerConfig.set(
                HistoryServerOptions.HISTORY_SERVER_WEB_DIR, hsDirectory.getAbsolutePath());
        historyServerConfig.set(
                HistoryServerOptions.HISTORY_SERVER_ARCHIVE_REFRESH_INTERVAL,
                Duration.ofMillis(100L));

        historyServerConfig.set(
                HistoryServerOptions.HISTORY_SERVER_CLEANUP_EXPIRED_JOBS, cleanupExpiredJobs);

        historyServerConfig.set(HistoryServerOptions.HISTORY_SERVER_WEB_PORT, 0);
        return historyServerConfig;
    }

    private static DashboardConfiguration getDashboardConfiguration(String baseUrl)
            throws Exception {
        Tuple2<Integer, String> response =
                HttpUtils.getFromHTTP(
                        baseUrl
                                + DashboardConfigurationHeaders.INSTANCE
                                        .getTargetRestEndpointURL());
        return OBJECT_MAPPER.readValue(response.f1, DashboardConfiguration.class);
    }

    private static MultipleJobsDetails getJobsOverview(String baseUrl) throws Exception {
        Tuple2<Integer, String> response = HttpUtils.getFromHTTP(baseUrl + JobsOverviewHeaders.URL);
        return OBJECT_MAPPER.readValue(response.f1, MultipleJobsDetails.class);
    }

    private static void runJob() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.fromData(1, 2, 3).sinkTo(new DiscardingSink<>());

        env.execute();
    }

    private static String createLegacyArchive(
            Path directory, long fileModifiedDate, boolean versionLessThan14) throws IOException {
        String jobId = createLegacyArchive(directory, versionLessThan14);
        File jobArchive = directory.resolve(jobId).toFile();
        jobArchive.setLastModified(fileModifiedDate);
        return jobId;
    }

    private static String createLegacyArchive(Path directory, boolean versionLessThan14)
            throws IOException {
        JobID jobId = JobID.generate();

        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = JACKSON_FACTORY.createGenerator(sw)) {
            try (JsonObject root = new JsonObject(gen)) {
                try (JsonArray finished = new JsonArray(gen, "finished")) {
                    try (JsonObject job = new JsonObject(gen)) {
                        gen.writeStringField("jid", jobId.toString());
                        gen.writeStringField("name", "testjob");
                        gen.writeStringField("state", JobStatus.FINISHED.name());

                        gen.writeNumberField("start-time", 0L);
                        gen.writeNumberField("end-time", 1L);
                        gen.writeNumberField("duration", 1L);
                        gen.writeNumberField("last-modification", 1L);

                        try (JsonObject tasks = new JsonObject(gen, "tasks")) {
                            gen.writeNumberField("total", 0);

                            if (versionLessThan14) {
                                gen.writeNumberField("pending", 0);
                            } else {
                                gen.writeNumberField("created", 0);
                                gen.writeNumberField("deploying", 0);
                                gen.writeNumberField("scheduled", 0);
                            }
                            gen.writeNumberField("running", 0);
                            gen.writeNumberField("finished", 0);
                            gen.writeNumberField("canceling", 0);
                            gen.writeNumberField("canceled", 0);
                            gen.writeNumberField("failed", 0);
                        }
                    }
                }
            }
        }
        String json = sw.toString();
        ArchivedJson archivedJson = new ArchivedJson("/joboverview", json);
        FsJobArchivist.archiveJob(
                new org.apache.flink.core.fs.Path(directory.toUri()),
                jobId,
                Collections.singleton(archivedJson));

        return jobId.toString();
    }

    private static final class JsonObject implements AutoCloseable {

        private final JsonGenerator gen;

        JsonObject(JsonGenerator gen) throws IOException {
            this.gen = gen;
            gen.writeStartObject();
        }

        private JsonObject(JsonGenerator gen, String name) throws IOException {
            this.gen = gen;
            gen.writeObjectFieldStart(name);
        }

        @Override
        public void close() throws IOException {
            gen.writeEndObject();
        }
    }

    private static final class JsonArray implements AutoCloseable {

        private final JsonGenerator gen;

        JsonArray(JsonGenerator gen, String name) throws IOException {
            this.gen = gen;
            gen.writeArrayFieldStart(name);
        }

        @Override
        public void close() throws IOException {
            gen.writeEndArray();
        }
    }
}
