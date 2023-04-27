package genericpubsub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.salesforce.eventbus.protobuf.*;

import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import utility.CommonContext;
import utility.ExampleConfigurations;

/**
 * A single-topic publisher that creates CarMaintenance events and publishes them. This example
 * uses Pub/Sub API's PublishStream RPC to publish events.
 *
 * Example:
 * ./run.sh genericpubsub.PublishStream
 *
 * @author sidd0610
 */
public class PublishStream extends CommonContext {
    private final int TIMEOUT_SECONDS = 30; // Max time we'll wait to finish streaming

    ClientCallStreamObserver<PublishRequest> requestObserver = null;

    private ByteString lastPublishedReplayId;

    public PublishStream(ExampleConfigurations exampleConfigurations) {
        super(exampleConfigurations);
        setupTopicDetails(exampleConfigurations.getTopic(), true, true);
    }

    /**
     * Publishes specified number of events using the PublishStream RPC.
     *
     * @param numEventsToPublish
     * @return ByteString
     * @throws Exception
     */
    public void publishStream(int numEventsToPublish) throws Exception {
        CountDownLatch finishLatch = new CountDownLatch(1);
        AtomicReference<CountDownLatch> finishLatchRef = new AtomicReference<>(finishLatch);
        final List<Status> errorStatuses = Lists.newArrayList();
        final List<PublishResponse> publishResponses = Lists.newArrayListWithExpectedSize(numEventsToPublish);
        AtomicInteger failed = new AtomicInteger(0);
        StreamObserver<PublishResponse> pubObserver = getDefaultPublishStreamObserver(errorStatuses, finishLatchRef,
                numEventsToPublish, publishResponses, failed);

        // construct the stream
        requestObserver = (ClientCallStreamObserver<PublishRequest>) asyncStub.publishStream(pubObserver);

        for (int i = 0; i < numEventsToPublish; i++) {
            requestObserver.onNext(generatePublishRequest(i));
        }

        validatePublishResponse(errorStatuses, finishLatch, numEventsToPublish,
                publishResponses, failed);
        requestObserver.onCompleted();
    }

    /**
     * Helper function to validate the PublishResponse received. Also prints the RPC id of the call.
     *
     * @param errorStatus
     * @param finishLatch
     * @param expectedResponseCount
     * @param publishResponses
     * @return
     * @throws Exception
     */
    private void validatePublishResponse(List<Status> errorStatus, CountDownLatch finishLatch,
                                               int expectedResponseCount, List<PublishResponse> publishResponses, AtomicInteger failed) throws Exception {
        String exceptionMsg;
        if (!finishLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            exceptionMsg = "[ERROR] publishStream timed out after: " + TIMEOUT_SECONDS + "sec";
            logger.error(exceptionMsg);
        }

        boolean receivedAllResponses = true;
        if (expectedResponseCount != publishResponses.size()) {
            receivedAllResponses = false;
            exceptionMsg = "[ERROR] PublishStream received: " + publishResponses.size() + " events instead of expected "
                    + expectedResponseCount;
            logger.error(exceptionMsg);

            errorStatus.stream().forEach(status -> {
                logger.error("[ERROR] Unexpected error status: " + status);
            });
        }

        if (failed.get() != 0 || !receivedAllResponses) {
            exceptionMsg = "[ERROR] Failed to publish all events. " + failed + " failed out of "
                    + expectedResponseCount;
            logger.error(exceptionMsg);
            throw new Exception(exceptionMsg);
        }
    }

    /**
     * Creates a ProducerEvent to be published in a PublishRequest.
     *
     * @param counter
     * @return
     * @throws IOException
     */
    private ProducerEvent generateProducerEvent(int counter) throws IOException {
        Schema schema = new Schema.Parser().parse(schemaInfo.getSchemaJson());
        GenericRecord event = createCarMaintenanceRecord(schema, counter);

        // Convert to byte array
        GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(event.getSchema());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().directBinaryEncoder(buffer, null);
        writer.write(event, encoder);

        return ProducerEvent.newBuilder().setSchemaId(schemaInfo.getSchemaId())
                .setPayload(ByteString.copyFrom(buffer.toByteArray())).build();
    }

    /**
     * Helper function to generate the PublishRequest with the generated ProducerEvent to be sent
     * using the PublishStream RPC
     *
     * @return PublishRequest
     * @throws IOException
     */
    private PublishRequest generatePublishRequest(int counter) throws IOException {
        ProducerEvent e = generateProducerEvent(counter);
        return PublishRequest.newBuilder().setTopicName(busTopicName).addEvents(e).build();
    }

    /**
     * Creates a StreamObserver for handling the incoming PublishResponse messages from the server.
     *
     * @param errorStatus
     * @param finishLatchRef
     * @param expectedResponseCount
     * @param publishResponses
     * @return
     */
    private StreamObserver<PublishResponse> getDefaultPublishStreamObserver(List<Status> errorStatus,
                                                                            AtomicReference<CountDownLatch> finishLatchRef, int expectedResponseCount,
                                                                            List<PublishResponse> publishResponses, AtomicInteger failed) {
        return new StreamObserver<>() {
            @Override
            public void onNext(PublishResponse publishResponse) {
                publishResponses.add(publishResponse);

                logger.info("Publish Call rpcId: " + publishResponse.getRpcId());

                for (PublishResult publishResult : publishResponse.getResultsList()) {
                    if (publishResult.hasError()) {
                        failed.incrementAndGet();
                        logger.error("[ERROR] Publishing event having correlationKey: " + publishResult.getCorrelationKey() +
                                " failed with error: " + publishResult.getError().getMsg());
                    } else {
                        logger.info("Event publish successful with correlationKey: " + publishResult.getCorrelationKey());
                        lastPublishedReplayId = publishResult.getReplayId();
                    }
                }

                if (publishResponses.size() == expectedResponseCount) {
                    finishLatchRef.get().countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                printStatusRuntimeException("Error during PublishStream", (Exception) t);
                errorStatus.add(Status.fromThrowable(t));
                finishLatchRef.get().countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Successfully published " + expectedResponseCount + " events at " + busTopicName + " for tenant " + tenantGuid);
                finishLatchRef.get().countDown();
            }
        };
    }

    public static void main(String[] args) throws IOException {
        ExampleConfigurations exampleConfigurations = new ExampleConfigurations("arguments.yaml");

        // Using the try-with-resource statement. The CommonContext class implements AutoCloseable in
        // order to close the resources used.
        try (PublishStream example = new PublishStream(exampleConfigurations)) {
            example.publishStream(exampleConfigurations.getNumberOfEventsToPublish());
        } catch (Exception e) {
            printStatusRuntimeException("Error During PublishStream", e);
        }
    }
}
