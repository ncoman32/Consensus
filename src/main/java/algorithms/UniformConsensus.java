package algorithms;

import static java.util.Objects.nonNull;
import static utils.SocketUtils.HUB_HOST;
import static utils.SocketUtils.HUB_PORT;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos;
import main.Paxos.AppDecide;
import main.Paxos.EpPropose;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.PlSend;
import main.Paxos.ProcessId;
import main.Paxos.UcDecide;
import main.Paxos.Value;
import utils.SystemLogger;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class UniformConsensus {

  private static final String ABSTRACTION = "uc";

  final List<ProcessId> involvedProcesses;
  final PerfectLink pl;
  final ProcessId self;
  volatile Value val;
  volatile boolean proposed;
  volatile ProcessId leader;
  EpochConsensus ep;
  boolean decided;
  int ets;
  int newts;
  ProcessId newLeader;

  public UniformConsensus(
      final List<ProcessId> involvedProcesses, final PerfectLink pl, final ProcessId self) {
    this.involvedProcesses = involvedProcesses;
    this.val = null;
    this.self = self;
    this.proposed = false;
    this.decided = false;
    this.ets = 0;
    this.leader = getMaxRank();
    this.newts = 0;
    this.newLeader = null;

    this.pl = pl;
    pl.addHandlerIfNotPresent(this::handle, ABSTRACTION);

    this.ep =
        new EpochConsensus(0, Value.getDefaultInstance(), leader, self, pl, involvedProcesses, 0);
    final Runnable uniformConsensusThread =
        () -> {
          while (true) {
            if (leader.getPort() == self.getPort() && nonNull(val) && !proposed) {
              this.proposed = true;
              SystemLogger.getInstance()
                  .info(String.format("[EP][%s] Sending EP_PROPOSE val = %s", self.getPort(), val));
              this.pl.enqueue(
                  Message.newBuilder()
                      .setType(Type.EP_PROPOSE)
                      .setAbstractionId(ABSTRACTION)
                      .setEpPropose(EpPropose.newBuilder().setValue(val).build())
                      .build());
            }
          }
        };
    new Thread(uniformConsensusThread).start();
  }

  private boolean handle(final Message message) {
    final Message.Type messageType = resolveMessageType(message);
    switch (messageType) {
      case EC_START_EPOCH:
        return handleEcStartEpoch(message);
      case EP_ABORTED:
        return handleEpAborted(message);
      case EP_DECIDE:
        return handleEpDecide(message);
      case UC_PROPOSE:
        return handleUcPropose(message);
      case UC_DECIDE:
        return sendResultToHub(message);
      default:
        return false;
    }
  }

  private boolean sendResultToHub(final Message message) {
    this.pl.enqueue(
        Message.newBuilder()
            .setType(Type.PL_SEND)
            .setPlSend(
                PlSend.newBuilder()
                    .setDestination(ProcessId.newBuilder().setPort(HUB_PORT).setHost(HUB_HOST))
                    .setMessage(
                        Message.newBuilder()
                            .setType(Type.APP_DECIDE)
                            .setAppDecide(
                                AppDecide.newBuilder()
                                    .setValue(message.getUcDecide().getValue())
                                    .build())
                            .build())
                    .build())
            .setAbstractionId(ABSTRACTION)
            .build());
    return true;
  }

  private boolean handleUcPropose(final Message message) {
    SystemLogger.getInstance()
        .info(
            String.format(
                "[EP][%s] Handling UC_PROPOSE. Proposed value = %s",
                self.getPort(), message.getUcPropose().getValue()));
    this.val = message.getUcPropose().getValue();
    return true;
  }

  private boolean handleEpDecide(final Message message) {
    SystemLogger.getInstance()
        .info(
            String.format(
                "[EP][%s] Handling EP_DECIDE, val =%s ",
                self.getPort(), message.getEpDecide().getValue()));
    if (message.getEpDecide().getEts() == ets) {
      if (!decided) {
        decided = true;
      }
      SystemLogger.getInstance()
          .info(
              String.format(
                  "[EP][%s] Sending UC_DECIDE, val = %s",
                  self.getPort(), message.getEpDecide().getValue()));
      pl.enqueue(
          Message.newBuilder()
              .setType(Type.UC_DECIDE)
              .setUcDecide(UcDecide.newBuilder().setValue(message.getEpDecide().getValue()).build())
              .setAbstractionId(ABSTRACTION)
              .build());
      return true;
    }
    return true;
  }

  private boolean handleEpAborted(final Message message) {
    if (message.getEpAborted().getEts() == ets) {
      SystemLogger.getInstance()
          .info(String.format("[EP][%s] Handling EP_ABORTED. Starting new ep", self.getPort()));
      this.ets = newts;
      this.leader = newLeader;
      this.proposed = false;
      this.ep =
          new EpochConsensus(
              message.getEpAborted().getValueTimestamp(),
              message.getEpAborted().getValue(),
              this.leader,
              self,
              pl,
              involvedProcesses,
              ets);
      return true;
    } else if (nonNull(this.ep) && message.getEpAborted().getEts() < this.ep.getEts()) {
      // i dont know why this happens ??? received message to abort an EP with lower ets
      // ignore the message
      return true;
    }
    return true;
  }

  private boolean handleEcStartEpoch(final Message message) {
    this.newts = message.getEcStartEpoch().getNewTimestamp();
    this.newLeader = message.getEcStartEpoch().getNewLeader();
    SystemLogger.getInstance()
        .info(String.format("[EP][%s] Handling EC_START_EPOCH", self.getPort()));
    if (!ep.isAborted()) {
      ep.abort();
    }
    return true;
  }

  private ProcessId getMaxRank() {
    return this.involvedProcesses.stream()
        .max(Comparator.comparing(ProcessId::getRank))
        .orElseThrow(NoSuchElementException::new);
  }

  private Type resolveMessageType(Paxos.Message message) {
    if (message.getType() == Type.BEB_DELIVER) {
      return message.getBebDeliver().getMessage().getType();
    }

    if (message.getType() == Type.PL_DELIVER && !message.getAbstractionId().equals("beb")) {
      return message.getPlDeliver().getMessage().getType();
    }
    return message.getType();
  }
}
