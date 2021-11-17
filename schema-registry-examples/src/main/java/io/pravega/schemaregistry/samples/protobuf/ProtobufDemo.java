/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.schemaregistry.samples.protobuf;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.GeneratedMessageV3;
import io.pravega.client.ClientConfig;
import io.pravega.client.EventStreamClientFactory;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.admin.impl.ReaderGroupManagerImpl;
import io.pravega.client.admin.impl.StreamManagerImpl;
import io.pravega.client.connection.impl.SocketConnectionFactoryImpl;
import io.pravega.client.stream.EventRead;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ReaderConfig;
import io.pravega.client.stream.ReaderGroupConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.schemaregistry.client.SchemaRegistryClient;
import io.pravega.schemaregistry.client.SchemaRegistryClientConfig;
import io.pravega.schemaregistry.client.SchemaRegistryClientFactory;
import io.pravega.schemaregistry.common.Either;
import io.pravega.schemaregistry.contract.data.Compatibility;
import io.pravega.schemaregistry.contract.data.GroupProperties;
import io.pravega.schemaregistry.contract.data.SchemaInfo;
import io.pravega.schemaregistry.contract.data.SerializationFormat;
import io.pravega.schemaregistry.samples.protobuf.generated.ProtobufTest;
import io.pravega.schemaregistry.serializer.protobuf.impl.ProtobufSerializerFactory;
import io.pravega.schemaregistry.serializer.protobuf.schemas.ProtobufSchema;
import io.pravega.schemaregistry.serializer.shared.impl.SerializerConfig;
import io.pravega.schemaregistry.serializers.SerializerFactory;
import io.pravega.shared.NameUtils;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sample class that demonstrates how to use Json Serializers and Deserializers provided by Schema registry's 
 * {@link SerializerFactory}.
 * Protobuf has multiple deserialization options 
 * 1. Deserialize into protobuf generated java class (schema on read).
 * 2. Deserialize into {@link DynamicMessage} using user supplied schema (schema on read). 
 * 3. Deserialize into {@link DynamicMessage} while retrieving writer schema. 
 * 4. Multiplexed Deserializer that deserializes data into one of java objects based on {@link SchemaInfo#getType}.
 */
public class ProtobufDemo {
    private final ClientConfig clientConfig;

    private final SchemaRegistryClient client;

    private ProtobufDemo(String pravegaUri, String registryUri) {
        clientConfig = ClientConfig.builder().controllerURI(URI.create(pravegaUri)).build();
        SchemaRegistryClientConfig config = SchemaRegistryClientConfig.builder().schemaRegistryUri(URI.create(registryUri)).build();
        client = SchemaRegistryClientFactory.withDefaultNamespace(config);
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options();

        Option pravegaUriOpt = new Option("p", "pravegaUri", true, "Pravega Uri");
        pravegaUriOpt.setRequired(true);
        options.addOption(pravegaUriOpt);

        Option registryUriOpt = new Option("r", "registryUri", true, "Registry Uri");
        registryUriOpt.setRequired(true);
        options.addOption(registryUriOpt);

        CommandLineParser parser = new BasicParser();

        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("-p pravegaUri -r registryUri", options);

            System.exit(-1);
        }

        String pravegaUri = cmd.getOptionValue("pravegaUri");
        String registryUri = cmd.getOptionValue("registryUri");

        ProtobufDemo demo = new ProtobufDemo(pravegaUri, registryUri);

        demo.demo();
        demo.demoMultipleEventTypesInStream();

        System.exit(0);
    }

    private void demo() throws Exception {
        System.out.println("Demo writing protobuf messages into pravega stream.");

        // create stream
        String scope = "scope";
        String stream = "protobuf";
        String groupId = NameUtils.getScopedStreamName(scope, stream);

        try (StreamManager streamManager = new StreamManagerImpl(clientConfig)) {
            streamManager.createScope(scope);
            streamManager.createStream(scope, stream, StreamConfiguration.builder().scalingPolicy(ScalingPolicy.fixed(1)).build());
            System.out.printf("Created Stream %s/%s%n", scope, stream);

            SerializationFormat serializationFormat = SerializationFormat.Protobuf;
            client.addGroup(groupId, new GroupProperties(serializationFormat,
                    Compatibility.allowAny(), false, ImmutableMap.of()));

            ProtobufSchema<ProtobufTest.Message1> schema = ProtobufSchema.of(ProtobufTest.Message1.class);

            SerializerConfig serializerConfig = SerializerConfig.builder()
                                                                .groupId(groupId)
                                                                .registerSchema(true)
                                                                .registryClient(client)
                                                                .build();
            // region writer
            Serializer<ProtobufTest.Message1> serializer = ProtobufSerializerFactory.serializer(serializerConfig, schema);
            EventStreamClientFactory clientFactory = EventStreamClientFactory.withScope(scope, clientConfig);

            EventStreamWriter<ProtobufTest.Message1> writer = clientFactory.createEventWriter(stream, serializer, EventWriterConfig.builder().build());
            ProtobufTest.Message1 message = ProtobufTest.Message1.newBuilder().setName("test").setInternal(ProtobufTest.InternalMessage.newBuilder().setValue(ProtobufTest.InternalMessage.Values.val1).build()).build();
            System.out.printf("Writing message %s%n", message.toString());
            writer.writeEvent(message).join();

            // endregion

            try (ReaderGroupManager readerGroupManager = new ReaderGroupManagerImpl(scope, clientConfig, new SocketConnectionFactoryImpl(clientConfig))) {
                // region read into specific schema
                System.out.println("Reading into protobuf generated object");

                String readerGroupName = UUID.randomUUID().toString();
                readerGroupManager.createReaderGroup(readerGroupName,
                        ReaderGroupConfig.builder().stream(NameUtils.getScopedStreamName(scope, stream)).disableAutomaticCheckpoints().build());

                Serializer<ProtobufTest.Message1> deserializer = ProtobufSerializerFactory.deserializer(serializerConfig, schema);

                EventStreamReader<ProtobufTest.Message1> reader = clientFactory.createReader("r1", readerGroupName, deserializer, ReaderConfig.builder().build());

                EventRead<ProtobufTest.Message1> event = reader.readNextEvent(1000);
                assert null != event.getEvent();
                System.out.printf("Message read into specific Message1 schema %s%n", event.getEvent().toString());
                // endregion

                // region generic read
                // 1. try without passing the schema. writer schema will be used to read
                System.out.println("Reading as dynamic message using writer's schema.");
                String rg2 = UUID.randomUUID().toString();
                readerGroupManager.createReaderGroup(rg2,
                        ReaderGroupConfig.builder().stream(NameUtils.getScopedStreamName(scope, stream)).disableAutomaticCheckpoints().build());

                Serializer<DynamicMessage> genericDeserializer = ProtobufSerializerFactory.genericDeserializer(serializerConfig, null);

                EventStreamReader<DynamicMessage> reader2 = clientFactory.createReader("r1", rg2, genericDeserializer, ReaderConfig.builder().build());

                EventRead<DynamicMessage> event2 = reader2.readNextEvent(1000);
                assert null != event2.getEvent();
                System.out.printf("Message read as DynamicMessage %s%n", event2.getEvent().toString());

                // 2. try with passing the schema. reader schema will be used to read
                System.out.println("Reading as dynamic message by explicitly supplying a schema to apply while reading.");
                String rg3 = UUID.randomUUID().toString();
                readerGroupManager.createReaderGroup(rg3,
                        ReaderGroupConfig.builder().stream(NameUtils.getScopedStreamName(scope, stream)).disableAutomaticCheckpoints().build());

                byte[] schemaBytes = client.getLatestSchemaVersion(groupId, null).getSchemaInfo().getSchemaData().array();
                DescriptorProtos.FileDescriptorSet descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(schemaBytes);

                ProtobufSchema<DynamicMessage> schema2 = ProtobufSchema.of(ProtobufTest.Message1.getDescriptor().getFullName(), descriptorSet);
                genericDeserializer = ProtobufSerializerFactory.genericDeserializer(serializerConfig, schema2);

                reader2 = clientFactory.createReader("r1", rg3, genericDeserializer, ReaderConfig.builder().build());

                event2 = reader2.readNextEvent(1000);
                assert null != event2.getEvent();
                System.out.printf("Message read as DynamicMessage by passing the schema in the application %s%n", event2.getEvent().toString());
                // endregion
            }
        }
    }

    private void demoMultipleEventTypesInStream() {
        System.out.println("Demo writing multiple types of protobuf messages into same pravega stream.");

        // create stream
        String scope = "scope";
        String stream = "protomultiplexed";
        String groupId = NameUtils.getScopedStreamName(scope, stream);

        try (StreamManager streamManager = new StreamManagerImpl(clientConfig)) {
            streamManager.createScope(scope);
            streamManager.createStream(scope, stream, StreamConfiguration.builder().scalingPolicy(ScalingPolicy.fixed(1)).build());
            System.out.printf("Created Stream %s/%s%n", scope, stream);

            SerializationFormat serializationFormat = SerializationFormat.Protobuf;
            client.addGroup(groupId, new GroupProperties(serializationFormat,
                    Compatibility.allowAny(),
                    true));

            ProtobufSchema<GeneratedMessageV3> schema1 = ProtobufSchema.ofGeneratedMessageV3(ProtobufTest.Message1.class);
            ProtobufSchema<GeneratedMessageV3> schema2 = ProtobufSchema.ofGeneratedMessageV3(ProtobufTest.Message2.class);
            ProtobufSchema<GeneratedMessageV3> schema3 = ProtobufSchema.ofGeneratedMessageV3(ProtobufTest.Message3.class);

            SerializerConfig serializerConfig = SerializerConfig.builder()
                                                                .groupId(groupId)
                                                                .registerSchema(true)
                                                                .registryClient(client)
                                                                .build();
            // region writer
            Map<Class<? extends GeneratedMessageV3>, ProtobufSchema<GeneratedMessageV3>> map = new HashMap<>();
            map.put(ProtobufTest.Message1.class, schema1);
            map.put(ProtobufTest.Message2.class, schema2);
            map.put(ProtobufTest.Message3.class, schema3);
            Serializer<GeneratedMessageV3> serializer = SerializerFactory.protobufMultiTypeSerializer(serializerConfig, map);
            EventStreamClientFactory clientFactory = EventStreamClientFactory.withScope(scope, clientConfig);

            EventStreamWriter<GeneratedMessageV3> writer = clientFactory.createEventWriter(stream, serializer, EventWriterConfig.builder().build());
            ProtobufTest.Message1 message1 = ProtobufTest.Message1.newBuilder().setName("message1").setInternal(ProtobufTest.InternalMessage.newBuilder().setValue(ProtobufTest.InternalMessage.Values.val1).build()).build();
            writer.writeEvent(message1).join();
            System.out.printf("Writing event: %s%n", message1.toString());

            ProtobufTest.Message2 message2 = ProtobufTest.Message2.newBuilder().setName("message2").setField1(0).build();
            writer.writeEvent(message2).join();
            System.out.printf("Writing event: %s%n", message2.toString());

            ProtobufTest.Message3 message3 = ProtobufTest.Message3.newBuilder().setName("message3").setField1(0).setField2(1).build();
            writer.writeEvent(message3).join();
            System.out.printf("Writing event: %s%n", message3.toString());

            // endregion

            // region read into specific schema
            System.out.println("Reading into specific generated objects.");
            try (ReaderGroupManager readerGroupManager = new ReaderGroupManagerImpl(scope, clientConfig, new SocketConnectionFactoryImpl(clientConfig))) {
                String rg = UUID.randomUUID().toString();
                readerGroupManager.createReaderGroup(rg,
                        ReaderGroupConfig.builder().stream(NameUtils.getScopedStreamName(scope, stream)).disableAutomaticCheckpoints().build());

                Serializer<GeneratedMessageV3> deserializer = SerializerFactory.protobufMultiTypeDeserializer(serializerConfig, map);

                EventStreamReader<GeneratedMessageV3> reader = clientFactory.createReader("r1", rg, deserializer, ReaderConfig.builder().build());

                EventRead<GeneratedMessageV3> event = reader.readNextEvent(1000);
                assert null != event.getEvent();
                assert event.getEvent() instanceof ProtobufTest.Message1;
                System.out.printf("Event read: %s%n", event.getEvent().toString());

                event = reader.readNextEvent(1000);
                assert null != event.getEvent();
                assert event.getEvent() instanceof ProtobufTest.Message2;
                System.out.printf("Event read: %s%n", event.getEvent().toString());

                event = reader.readNextEvent(1000);
                assert null != event.getEvent();
                assert event.getEvent() instanceof ProtobufTest.Message3;
                System.out.printf("Event read: %s%n", event.getEvent().toString());

                // endregion
                // region read into writer schema
                System.out.println("Reading as dynamic message using writer's schema.");
                String rg2 = UUID.randomUUID().toString();
                readerGroupManager.createReaderGroup(rg2,
                        ReaderGroupConfig.builder().stream(NameUtils.getScopedStreamName(scope, stream)).disableAutomaticCheckpoints().build());

                Serializer<DynamicMessage> genericDeserializer = ProtobufSerializerFactory.genericDeserializer(serializerConfig, null);

                EventStreamReader<DynamicMessage> reader2 = clientFactory.createReader("r1", rg2, genericDeserializer, ReaderConfig.builder().build());

                EventRead<DynamicMessage> genEvent = reader2.readNextEvent(1000);
                assert null != genEvent.getEvent();
                System.out.printf("Event read: %s%n", genEvent.getEvent().toString());

                genEvent = reader2.readNextEvent(1000);
                assert null != genEvent.getEvent();
                System.out.printf("Event read: %s%n", genEvent.getEvent().toString());

                genEvent = reader2.readNextEvent(1000);
                assert null != genEvent.getEvent();
                System.out.printf("Event read: %s%n", genEvent.getEvent().toString());

                // endregion

                // region read using multiplexed and generic record combination
                System.out.println("Reading into specific generated objects for Message1 and Message2 and DynamicMessage for others.");

                String rg3 = UUID.randomUUID().toString();
                readerGroupManager.createReaderGroup(rg3,
                        ReaderGroupConfig.builder().stream(NameUtils.getScopedStreamName(scope, stream)).disableAutomaticCheckpoints().build());

                Map<Class<? extends GeneratedMessageV3>, ProtobufSchema<GeneratedMessageV3>> map2 = new HashMap<>();
                // add only two schemas
                map2.put(ProtobufTest.Message1.class, schema1);
                map2.put(ProtobufTest.Message2.class, schema2);

                Serializer<Either<GeneratedMessageV3, DynamicMessage>> eitherDeserializer =
                        SerializerFactory.protobufTypedOrGenericDeserializer(serializerConfig, map2);

                EventStreamReader<Either<GeneratedMessageV3, DynamicMessage>> reader3 = clientFactory.createReader("r1", rg3, eitherDeserializer, ReaderConfig.builder().build());

                EventRead<Either<GeneratedMessageV3, DynamicMessage>> e1 = reader3.readNextEvent(1000);
                assert e1.getEvent() != null;
                assert e1.getEvent().isLeft();
                assert e1.getEvent().getLeft() instanceof ProtobufTest.Message1;
                System.out.printf("Event read: %s%n", e1.getEvent().getLeft().toString());

                e1 = reader3.readNextEvent(1000);
                assert e1.getEvent().isLeft();
                assert e1.getEvent().getLeft() instanceof ProtobufTest.Message2;
                System.out.printf("Event read: %s%n", e1.getEvent().getLeft().toString());

                e1 = reader3.readNextEvent(1000);
                assert e1.getEvent().isRight();
                System.out.printf("Event read: %s%n", e1.getEvent().getRight().toString());
                //endregion
            }
        }
    }
}

