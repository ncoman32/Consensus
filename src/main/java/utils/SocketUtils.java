package utils;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import main.Paxos.AppRegistration;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.NetworkMessage;

public final class SocketUtils {

  // The hub port
  public static final int HUB_PORT = 5000;
  // The hub host. Usually this is the processes host as well
  public static final String HUB_HOST = "127.0.0.1";

  public static AsynchronousServerSocketChannel PROCESS_SERVER;

  private SocketUtils() {
    throw new UnsupportedOperationException("Class cannot be instantiated");
  }

  public static Message readMessage(final AsynchronousSocketChannel clientConnection)
      throws ExecutionException, InterruptedException, InvalidProtocolBufferException {
    final ByteBuffer buffer = ByteBuffer.allocate(1024);
    int numberOfBytesReceived = clientConnection.read(buffer).get();
    byte[] writtenBytes = new byte[numberOfBytesReceived];
    buffer.rewind();
    buffer.get(writtenBytes);

    byte[] messageBytes = new byte[numberOfBytesReceived - 4];
    System.arraycopy(writtenBytes, 4, messageBytes, 0, numberOfBytesReceived - 4);
    return Message.parseFrom(messageBytes);
  }

  public static Message registerProcess(
      final String processHost, final int processPort, final int processIndex, final String owner)
      throws InterruptedException, ExecutionException, IOException {
    writeMessageOnSocket(
        Message.newBuilder()
            .setType(Type.NETWORK_MESSAGE)
            .setNetworkMessage(
                NetworkMessage.newBuilder()
                    .setMessage(
                        Message.newBuilder()
                            .setType(Type.APP_REGISTRATION)
                            .setAppRegistration(
                                AppRegistration.newBuilder()
                                    .setOwner(owner)
                                    .setIndex(processIndex)
                                    .build()))
                    .setSenderListeningPort(processPort)
                    .setSenderHost(processHost)
                    .build())
            .build(),
        HUB_HOST,
        HUB_PORT);
    return getAppProposeMsg(processHost, processPort);
  }

  /**
   * Writes a message on a socket channel as bytes array.
   *
   * @param message the message to be written.Has to be a NETWORK_MESSAGE, not a PL_SEND message.
   * @param receiverHost the receiving process host.
   * @param receiverPort the receiving process port.
   * @throws IOException
   * @throws ExecutionException
   * @throws InterruptedException
   */
  private static void writeMessageOnSocket(
      final Message message, final String receiverHost, final int receiverPort)
      throws IOException, ExecutionException, InterruptedException {
    final InetSocketAddress hostAddress = new InetSocketAddress(receiverHost, receiverPort);
    final AsynchronousSocketChannel client = AsynchronousSocketChannel.open();
    final Future<Void> connectionResult = client.connect(hostAddress);

    // connect to host
    connectionResult.get();

    final byte[] messageBytes = message.toByteArray();

    // Build the request
    // first 4 bytes the =  the message length
    final ByteBuffer requestData = ByteBuffer.wrap(messageBytes);

    final ByteBuffer request =
        ByteBuffer.allocate(4 + requestData.array().length)
            .putInt(messageBytes.length)
            .put(requestData)
            .compact();

    // Write to host
    client.write(request).get();

    // close connection
    client.close();
  }

  private static Message getAppProposeMsg(final String processHost, final int processPort)
      throws IOException, ExecutionException, InterruptedException {

    final AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open(
            AsynchronousChannelGroup.withThreadPool(Executors.newFixedThreadPool(6)));

    server.bind(new InetSocketAddress(processHost, processPort));
    Future<AsynchronousSocketChannel> clientConnection = server.accept();
    final Message appPropose = readMessage(clientConnection.get());
    PROCESS_SERVER = server;
    return appPropose;
  }

  /**
   * Sends a PL_NETWORK message on a socket channel as bytes array.
   *
   * @param networkMessage the message to be sent. It has to be a NETWORK_MESSAGE, not a PL_SEND
   *     message
   */
  public static void send(
      final Message networkMessage, final String receiverHost, final int receiverPort) {
    try {
      writeMessageOnSocket(networkMessage, receiverHost, receiverPort);
    } catch (IOException | ExecutionException | InterruptedException e) {
      e.printStackTrace();
    }
  }
}
