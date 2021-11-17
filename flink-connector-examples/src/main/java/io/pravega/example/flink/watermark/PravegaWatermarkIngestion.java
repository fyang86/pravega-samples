/*
 * Copyright (c) 2018 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 */
package io.pravega.example.flink.watermark;

import io.pravega.client.EventStreamClientFactory;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.impl.JavaSerializer;
import io.pravega.connectors.flink.PravegaConfig;
import org.apache.flink.api.java.utils.ParameterTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/*
 * Parameters
 *     -controller tcp://<controller_host>:<port>, e.g., tcp://localhost:9090
 *
 * Functionality
 *     - Ingest simulated sine-wave sensor data with event timestamp into a Pravega stream(watermark-examples/raw-data).
 */
public class PravegaWatermarkIngestion {

    private static final Logger LOG = LoggerFactory.getLogger(PravegaWatermarkIngestion.class);

    public static void main(String[] args) throws InterruptedException {
        ParameterTool params = ParameterTool.fromArgs(args);
        // The writer will contact with the Pravega controller to get information about segments.
        URI pravegaControllerURI = URI.create(params.get(Constants.CONTROLLER_ADDRESS_PARAM, Constants.CONTROLLER_ADDRESS));

        // StreamManager helps us to easily manage streams and copes.
        StreamManager streamManager = StreamManager.create(pravegaControllerURI);

        // A scope is a namespace that will be used to group streams (e.g., like dirs and files).
        streamManager.createScope(Constants.DEFAULT_SCOPE);

        PravegaConfig pravegaConfig = PravegaConfig.fromDefaults()
                .withControllerURI(pravegaControllerURI)
                .withDefaultScope(Constants.DEFAULT_SCOPE);

        // Here we configure the new stream (e.g. scaling policy, retention policy).
        StreamConfiguration streamConfiguration = StreamConfiguration.builder()
                .scalingPolicy(ScalingPolicy.fixed(Constants.PARALLELISM))
                .build();

        // Create a Pravega stream to write data (if it does not exist yet).
        streamManager.createStream(Constants.DEFAULT_SCOPE, Constants.RAW_DATA_STREAM, streamConfiguration);

        // Create the client factory to instantiate writers and readers.
        try (EventStreamClientFactory clientFactory = EventStreamClientFactory.withScope(Constants.DEFAULT_SCOPE, pravegaConfig.getClientConfig())) {

            // Create a writer to write events in the stream.
            EventStreamWriter<SensorData> writer = clientFactory.createEventWriter("watermarking-events",
                    Constants.RAW_DATA_STREAM,
                    new JavaSerializer<>(), EventWriterConfig.builder().build());

            for (int i = 1; i <= Constants.EVENTS_NUMBER; i++) {
                // Write an event for each sensor.
                long eventTime = Constants.STARTING_EVENT_TIME + i * Constants.EVENT_TIME_PERIOD;
                for (int sensorId = 0; sensorId < Constants.SENSOR_NUMBER; sensorId++) {
                    // Different starting values per sensor.
                    final SensorData value = new SensorData(sensorId, Math.sin(i * Constants.EVENT_VALUE_INCREMENT + sensorId), eventTime);
                    writer.writeEvent(String.valueOf(sensorId), value);
                    LOG.warn("Writing event: {} (routing key {}).", value, sensorId);
                }

                writer.flush();
                Thread.sleep(Constants.WRITER_SLEEP_MS);

                // Notify the event time every 20 events
                if (i % 20 == 0) {
                    writer.noteTime(eventTime);
                }
            }

            writer.close();
        }

        System.exit(0);
    }
}
