package algorithms;

import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.NetworkMessage;
import main.Paxos.PlDeliver;
import main.Paxos.ProcessId;
import utils.SocketUtils;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class PerfectLink {

  final AsynchronousServerSocketChannel server;
  final CopyOnWriteArrayList<Function<Message, Boolean>> messageHandlers;
  final Set<String> abstractions;
  final ProcessId systemProcessId;
  final String systemId;
  volatile CopyOnWriteArrayList<Message> messageQueue;
  volatile boolean listenNetwork;

  public PerfectLink(
      final AsynchronousServerSocketChannel server,
      final ProcessId systemProcessId,
      final String systemId) {
    this.listenNetwork = false;
    this.systemProcessId = systemProcessId;

    this.messageHandlers = new CopyOnWriteArrayList<>();
    messageHandlers.add(this::handlePlSend);

    this.messageQueue = new CopyOnWriteArrayList<>();
    this.abstractions = new HashSet<>();
    this.systemId = systemId;

    this.server = server;
  }

  public void startNetworkListener() {
    this.listenNetwork = true;
    listenIncomingMessages();
  }

  private void listenIncomingMessages() {
    final Runnable networkListener =
        () -> {
          while (listenNetwork) {
            try {
              final AsynchronousSocketChannel clientConnection = server.accept().get();
              final Message receivedMessage = SocketUtils.readMessage(clientConnection);
              final Message plDeliverMessage = networkMessageToPlDeliverMessage(receivedMessage);
              messageQueue.add(plDeliverMessage);
            } catch (InterruptedException | InvalidProtocolBufferException | ExecutionException e) {
              e.printStackTrace();
            }
          }
        };
    new Thread(networkListener).start();
  }

  public void enqueue(final Message message) {
    this.messageQueue.add(message);
  }

  public void addFirst(final Message message) {
    CopyOnWriteArrayList<Message> newMessageQueue = new CopyOnWriteArrayList<>();
    newMessageQueue.add(message);
    newMessageQueue.addAll(messageQueue);
    this.messageQueue = newMessageQueue;
  }

  public void addHandlerIfNotPresent(
      final Function<Message, Boolean> handler, final String abstraction) {
    if (!abstractions.contains(abstraction)) {
      this.messageHandlers.add(handler);
      this.abstractions.add(abstraction);
    }
  }

  public synchronized void eventLoop() {
    Iterator<Message> it = this.messageQueue.iterator();
    boolean result;
    if (it.hasNext()) {
      final Message receivedMessage = it.next();
      result =
          messageHandlers.stream()
              .map(handler -> handler.apply(receivedMessage))
              .reduce(Boolean::logicalOr)
              .orElse(false);

      if (result) {
        messageQueue.remove(receivedMessage);
      } else if (receivedMessage.getPlDeliver().getMessage().getType() == Type.valueOf(0)) {
        messageQueue.remove(receivedMessage);
      }
    }
  }

  private boolean handlePlSend(final Message message) {
    if (message.getType() == Type.PL_SEND) {
      final Message networkMessage =
          Message.newBuilder()
              .setSystemId(systemId)
              .setAbstractionId(message.getAbstractionId())
              .setNetworkMessage(
                  NetworkMessage.newBuilder()
                      .setSenderListeningPort(systemProcessId.getPort())
                      .setSenderHost(systemProcessId.getHost())
                      .setMessage(message.getPlSend().getMessage())
                      .build())
              .build();
      SocketUtils.send(
          networkMessage,
          message.getPlSend().getDestination().getHost(),
          message.getPlSend().getDestination().getPort());
      return true;
    }
    return false;
  }

  private Message networkMessageToPlDeliverMessage(final Message receivedMessage) {
    return Message.newBuilder()
        .setType(Type.PL_DELIVER)
        .setPlDeliver(
            PlDeliver.newBuilder()
                .setSender(
                    ProcessId.newBuilder()
                        .setPort(receivedMessage.getNetworkMessage().getSenderListeningPort())
                        .setHost(receivedMessage.getNetworkMessage().getSenderHost())
                        .build())
                .setMessage(receivedMessage.getNetworkMessage().getMessage())
                .build())
        .setAbstractionId(receivedMessage.getAbstractionId())
        .build();
  }

  public void removeEpHandler(final String epAbstraction) {

    if (abstractions.contains(epAbstraction)) {
      this.abstractions.remove(epAbstraction);
      this.messageHandlers.remove(messageHandlers.size() - 1);
    }
  }
}
