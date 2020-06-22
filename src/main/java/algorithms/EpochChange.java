package algorithms;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos.BebBroadcast;
import main.Paxos.EcNack_;
import main.Paxos.EcNewEpoch_;
import main.Paxos.EcStartEpoch;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.PlSend;
import main.Paxos.ProcessId;
import utils.SystemLogger;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class EpochChange {

  private static final String ABSTRACTION = "ec";
  private PerfectLink pl;
  private List<ProcessId> involvedProcesses;
  private ProcessId self;
  private ProcessId trusted;
  private int lastts;
  private int ts;

  public EpochChange(
      final PerfectLink pl, final List<ProcessId> involvedProcesses, final ProcessId self) {
    this.pl = pl;
    this.involvedProcesses = involvedProcesses;
    this.self = self;
    this.trusted = getMaxRank();
    this.lastts = 0;
    this.ts = self.getRank();
    this.pl.addHandlerIfNotPresent(this::handle, ABSTRACTION);
  }

  private boolean handle(final Message message) {
    final Message.Type messageType = resolveMessageType(message);
    switch (messageType) {
      case ELD_TRUST:
        return handleEldTrust(message);
      case EC_NEW_EPOCH_:
        return handleEcNewEpoch(message);
      case EC_NACK_:
        return handleNack();
      default:
        return false;
    }
  }

  private boolean handleNack() {
    if (this.trusted.getPort() == this.self.getPort()) {
      SystemLogger.getInstance().info(String.format("[EC][%s] Handling Nack", self.getPort()));
      this.ts = this.ts + involvedProcesses.size();
      this.pl.enqueue(
          Message.newBuilder()
              .setType(Type.BEB_BROADCAST)
              .setBebBroadcast(
                  BebBroadcast.newBuilder()
                      .setMessage(
                          Message.newBuilder()
                              .setType(Type.EC_NEW_EPOCH_)
                              .setEcNewEpoch(EcNewEpoch_.newBuilder().setTimestamp(this.ts).build())
                              .build())
                      .build())
              .build());
    }
    return true;
  }

  private boolean handleEldTrust(final Message message) {
    this.trusted = message.getEldTrust().getProcess();
    SystemLogger.getInstance()
        .info(String.format("[EC][%s] Handling ELD_TRUST %s", self.getPort(), message));
    if (self.getPort() == trusted.getPort()) {
      this.ts = ts + involvedProcesses.size();
      SystemLogger.getInstance()
          .info(
              String.format(
                  "[EC][%s] Handling ELD_TRUST. Boradcasting EC_NEW_EPOCH", self.getPort()));
      this.pl.enqueue(
          Message.newBuilder()
              .setType(Type.BEB_BROADCAST)
              .setBebBroadcast(
                  BebBroadcast.newBuilder()
                      .setMessage(
                          Message.newBuilder()
                              .setType(Type.EC_NEW_EPOCH_)
                              .setEcNewEpoch(EcNewEpoch_.newBuilder().setTimestamp(ts).build())
                              .build())
                      .build())
              .build());
    }
    return true;
  }

  private boolean handleEcNewEpoch(final Message message) {
    SystemLogger.getInstance()
        .info(String.format("[EC][%s] Handling NEW_EPOCH %s", self.getPort(), message));
    final ProcessId leader = findLeaderByPort(message.getBebDeliver().getSender().getPort());
    final int newts = message.getBebDeliver().getMessage().getEcNewEpoch().getTimestamp();
    if (leader.getPort() == this.trusted.getPort() && newts > this.lastts) {
      this.lastts = newts;
      SystemLogger.getInstance()
          .info(String.format("[EC][%s] Trigger EC_START_EPOCH", self.getPort()));
      this.pl.enqueue(
          Message.newBuilder()
              .setType(Type.EC_START_EPOCH)
              .setEcStartEpoch(
                  EcStartEpoch.newBuilder().setNewLeader(leader).setNewTimestamp(newts).build())
              .build());
    } else {
      SystemLogger.getInstance().info(String.format("[EC][%s] Trigger EC_NACK", self.getPort()));
      this.pl.enqueue(
          Message.newBuilder()
              .setType(Type.PL_SEND)
              .setPlSend(
                  PlSend.newBuilder()
                      .setMessage(
                          Message.newBuilder()
                              .setType(Type.EC_NACK_)
                              .setEcNack(EcNack_.newBuilder().build()))
                      .setDestination(leader)
                      .build())
              .build());
    }
    return true;
  }

  private Type resolveMessageType(Message message) {
    if (message.getType() == Type.BEB_DELIVER) {
      return message.getBebDeliver().getMessage().getType();
    }

    if (message.getType() == Type.PL_DELIVER
        && message.getPlDeliver().getMessage().getType() == Type.EC_NACK_) {
      return Type.EC_NACK_;
    }

    return message.getType();
  }

  private ProcessId getMaxRank() {
    return this.involvedProcesses.stream()
        .max(Comparator.comparing(ProcessId::getRank))
        .orElseThrow(NoSuchElementException::new);
  }

  private ProcessId findLeaderByPort(final int port) {
    return this.involvedProcesses.stream()
        .filter(processId -> processId.getPort() == port)
        .findFirst()
        .orElseThrow(NoSuchElementException::new);
  }
}
