package algorithms;

import static java.util.Objects.nonNull;

import com.sun.tools.javac.util.Pair;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos;
import main.Paxos.BebBroadcast;
import main.Paxos.EpAborted;
import main.Paxos.EpAccept_;
import main.Paxos.EpDecide;
import main.Paxos.EpDecided_;
import main.Paxos.EpRead_;
import main.Paxos.EpState_;
import main.Paxos.EpWrite_;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.PlSend;
import main.Paxos.ProcessId;
import main.Paxos.Value;
import utils.SystemLogger;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class EpochConsensus {

  private static int ABSTRACTION_COUNTER = 0;

  private final String absraction;
  private final Thread epThread;
  final PerfectLink pl;
  volatile List<ProcessId> involvedProcesses;
  volatile CopyOnWriteArrayList<Pair<Integer, Value>> states;
  volatile ProcessId leader;
  volatile ProcessId self;
  int ets;
  int valts;
  Value val;
  int tmpval;
  volatile int accepted;
  volatile boolean isAborted = false;

  public EpochConsensus(
      final int valts,
      final Value val,
      final ProcessId leader,
      final ProcessId self,
      final PerfectLink pl,
      final List<ProcessId> involvedProcesses,
      final int ets) {
    this.absraction = "ep" + ABSTRACTION_COUNTER;
    ABSTRACTION_COUNTER++;

    this.ets = ets;
    this.valts = valts;
    this.val = val;
    this.tmpval = -1;
    this.accepted = 0;
    this.states = new CopyOnWriteArrayList<>();

    this.leader = leader;
    this.self = self;
    this.involvedProcesses = involvedProcesses;
    this.pl = pl;
    pl.addHandlerIfNotPresent(this::handle, this.absraction);
    Runnable epochConsensusThread =
        () -> {
          while (!isAborted) {
            if (this.accepted > (this.involvedProcesses.size() / 2)
                && this.self.getPort() == leader.getPort()) {
              this.accepted = 0;
              SystemLogger.getInstance()
                  .info(
                      String.format(
                          "[EP][%s] Sending EP_DECIDED" + ", val = %s", self.getPort(), tmpval));
              this.pl.enqueue(
                  Message.newBuilder()
                      .setType(Type.BEB_BROADCAST)
                      .setBebBroadcast(
                          BebBroadcast.newBuilder()
                              .setMessage(
                                  Message.newBuilder()
                                      .setType(Type.EP_DECIDED_)
                                      .setEpDecided(
                                          EpDecided_.newBuilder()
                                              .setValue(
                                                  Value.newBuilder()
                                                      .setDefined(true)
                                                      .setV(tmpval)
                                                      .build()))
                                      .build())
                              .build())
                      .build());
            }

            if ((this.states.size() > (this.involvedProcesses.size() / 2))
                && this.self.getPort() == this.leader.getPort()) {
              final Pair<Integer, Value> highest = highest(states);
              if (nonNull(highest) && highest.snd.getV() > 0) {
                this.tmpval = highest.snd.getV();
              }
              states = new CopyOnWriteArrayList<>();
              SystemLogger.getInstance()
                  .info(
                      String.format(
                          "[EP][%s] Sending EP_WRITE %s, val = ", self.getPort(), tmpval));
              this.pl.enqueue(
                  Message.newBuilder()
                      .setType(Type.BEB_BROADCAST)
                      .setBebBroadcast(
                          BebBroadcast.newBuilder()
                              .setMessage(
                                  Message.newBuilder()
                                      .setType(Type.EP_WRITE_)
                                      .setEpWrite(
                                          EpWrite_.newBuilder()
                                              .setValue(
                                                  Value.newBuilder()
                                                      .setDefined(true)
                                                      .setV(this.tmpval)
                                                      .build())
                                              .build())
                                      .build())
                              .build())
                      .build());
            }
          }
        };
    epThread = new Thread(epochConsensusThread);
    epThread.start();
  }

  private Pair<Integer, Value> highest(final List<Pair<Integer, Value>> states) {
    if (states.isEmpty()) {
      return null;
    }
    Pair<Integer, Value> highest = states.get(0);
    for (Pair<Integer, Value> s : states) {
      if (s.fst > highest.fst) {
        highest = s;
      }
    }
    return highest;
  }

  private boolean handle(final Message message) {
    if (this.isAborted) {
      return false;
    }
    final Message.Type messageType = resolveMessageType(message);
    switch (messageType) {
      case EP_PROPOSE:
        return handleEpPropose(message);
      case EP_READ_:
        return handleEpRead();
      case EP_STATE_:
        return handleEpState(message);
      case EP_WRITE_:
        return handleEpWrite(message);
      case EP_ACCEPT_:
        return handleEpAccept();
      case EP_DECIDED_:
        return handleEpDecided(message);
      default:
        return false;
    }
  }

  public boolean abort() {
    SystemLogger.getInstance()
        .info(String.format("[EP][%s] Handling EP_ABORT. Aborting EP", self.getPort()));
    this.isAborted = true;
    this.pl.addFirst(
        Message.newBuilder()
            .setType(Type.EP_ABORTED)
            .setEpAborted(
                EpAborted.newBuilder()
                    .setValueTimestamp(valts)
                    .setEts(this.ets)
                    .setValue(this.val)
                    .build())
            .build());
    this.pl.removeEpHandler(this.absraction);
    try {
      epThread.join();
      epThread.stop();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    return true;
  }

  private boolean handleEpDecided(final Message message) {
    SystemLogger.getInstance()
        .info(String.format("[EP][%s] Handling EP_DECIDED %s", self.getPort(), message));
    SystemLogger.getInstance()
        .info(String.format("[EP][%s] Handling EP_DECIDED. Sending EP_DECIDE.", self.getPort()));
    this.pl.addFirst(
        Message.newBuilder()
            .setType(Type.EP_DECIDE)
            .setEpDecide(
                EpDecide.newBuilder()
                    .setValue(message.getBebDeliver().getMessage().getEpDecided().getValue())
                    .setEts(this.ets)
                    .build())
            .setAbstractionId(this.absraction)
            .build());
    return true;
  }

  private boolean handleEpAccept() {
    SystemLogger.getInstance().info(String.format("[EP][%s] Handling EP_ACCEPT", self.getPort()));
    if (this.self.getPort() != leader.getPort()) {
      return true;
    }
    this.accepted = accepted + 1;
    return true;
  }

  private boolean handleEpWrite(final Message message) {
    SystemLogger.getInstance()
        .info(String.format("[EP][%s] Handling EP_WRITE %s", self.getPort(), message));

    this.val = message.getBebDeliver().getMessage().getEpWrite().getValue();
    this.valts = ets;
    SystemLogger.getInstance()
        .info(
            String.format(
                "[EP][%s] Handling EP_WRITE. Sending EP_ACCEPT_ to leader", self.getPort()));
    pl.enqueue(
        Message.newBuilder()
            .setType(Type.PL_SEND)
            .setPlSend(
                PlSend.newBuilder()
                    .setDestination(leader)
                    .setMessage(
                        Message.newBuilder()
                            .setType(Type.EP_ACCEPT_)
                            .setEpAccept(EpAccept_.newBuilder().build())
                            .build())
                    .build())
            .setAbstractionId(this.absraction)
            .build());
    return true;
  }

  private boolean handleEpState(final Message message) {
    if (this.self.getPort() == this.leader.getPort()) {
      SystemLogger.getInstance()
          .info(String.format("[EP][%s] Handling EP_STATE %s", self.getPort(), message));
      this.states.add(
          Pair.of(
              message.getPlDeliver().getMessage().getEpState().getValueTimestamp(),
              message.getPlDeliver().getMessage().getEpState().getValue()));
    }
    return true;
  }

  private boolean handleEpRead() {
    SystemLogger.getInstance().info(String.format("[EP][%s] Handle EP_READ", self.getPort()));
    SystemLogger.getInstance()
        .info(
            String.format("[EP][%s] Handling EP_READ. Sending EP_STATE to leader", self.getPort()));
    this.pl.enqueue(
        Message.newBuilder()
            .setType(Type.PL_SEND)
            .setPlSend(
                PlSend.newBuilder()
                    .setDestination(leader)
                    .setMessage(
                        Message.newBuilder()
                            .setType(Type.EP_STATE_)
                            .setEpState(
                                EpState_.newBuilder()
                                    .setValue(this.val)
                                    .setValueTimestamp(this.valts)
                                    .build())
                            .build())
                    .build())
            .build());
    return true;
  }

  private boolean handleEpPropose(final Message message) {
    if (this.self.getPort() == this.leader.getPort()) {
      SystemLogger.getInstance()
          .info(String.format("[EP][%s] Handling EP_PROPOSE %s", self.getPort(), message));
      this.tmpval = message.getEpPropose().getValue().getV();
      SystemLogger.getInstance()
          .info(
              String.format("[EP][%s] Handling EP_PROPOSE. Broadcasting EP_READ", self.getPort()));
      pl.enqueue(
          Message.newBuilder()
              .setType(Type.BEB_BROADCAST)
              .setBebBroadcast(
                  BebBroadcast.newBuilder()
                      .setMessage(
                          Message.newBuilder()
                              .setType(Type.EP_READ_)
                              .setEpRead(EpRead_.newBuilder().build()))
                      .build())
              .build());
      return true;
    }
    return true;
  }

  private Type resolveMessageType(Paxos.Message message) {
    if (message.getType() == Type.BEB_DELIVER) {
      return message.getBebDeliver().getMessage().getType();
    }

    if (message.getType() == Type.PL_DELIVER) {
      if (message.getPlDeliver().getMessage().getType() == Type.EP_ACCEPT_) {
        return Type.EP_ACCEPT_;
      }
      if (message.getPlDeliver().getMessage().getType() == Type.EP_STATE_) {
        return Type.EP_STATE_;
      }
    }
    return message.getType();
  }

  public boolean isAborted() {
    return isAborted;
  }

  public int getEts() {
    return ets;
  }
}
