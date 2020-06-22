package algorithms;

import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos.EpfdHeartbeatReply_;
import main.Paxos.EpfdHeartbeatRequest_;
import main.Paxos.EpfdRestore;
import main.Paxos.EpfdSuspect;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.PlSend;
import main.Paxos.ProcessId;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class EventuallyPerfectFailureDetector {

  private static final String ABSTRACTION = "epfd";

  final PerfectLink pl;
  final Set<ProcessId> processes;
  volatile Set<ProcessId> aliveProcesses;
  volatile Set<ProcessId> suspectedProcesses;
  volatile int delay;
  volatile boolean startTimer = true;
  int delta;

  /**
   * Constructor is equivalent of event Init
   *
   * @param processes the processes involved in the consensus algorithm.
   * @param delta value used to increase the timer delay
   */
  public EventuallyPerfectFailureDetector(
      final Set<ProcessId> processes, final int delta, final PerfectLink pl) {
    this.pl = pl;
    this.processes = processes;
    this.aliveProcesses = processes;
    this.suspectedProcesses = new HashSet<>();
    this.delta = delta;
    this.delay = delta;
    final Runnable timerThread =
        () -> {
          while (true) {
            if (startTimer) {
              startTimer();
            }
          }
        };
    pl.addHandlerIfNotPresent(this::handle, ABSTRACTION);
    new Thread(timerThread).start();
  }

  private void startTimer() {
    try {
      Thread.sleep(this.delay);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    startTimer = false;
    pl.enqueue(Message.newBuilder().setType(Type.EPFD_TIMEOUT).build());
  }

  private boolean handle(final Message message) {

    final Message.Type type = resolveMessageType(message);
    switch (type) {
      case EPFD_TIMEOUT:
        return handleTimeout();
      case EPFD_HEARTBEAT_REQUEST:
        return handleHeartbeatRequest(message);
      case EPFD_HEARTBEAT_REPLY:
        return handleHeartbeatReply(message);
      default:
        return false;
    }
  }

  private boolean handleTimeout() {
    Set<ProcessId> intersection = new HashSet<>(suspectedProcesses);
    intersection.retainAll(aliveProcesses);
    if (!intersection.isEmpty()) {
      this.delay += this.delta;
    }

    processes.forEach(
        processId -> {
          if (!aliveProcesses.contains(processId) && !suspectedProcesses.contains(processId)) {
            suspectedProcesses.add(processId);
            this.pl.enqueue(
                Message.newBuilder()
                    .setType(Type.EPFD_SUSPECT)
                    .setEpfdSuspect(EpfdSuspect.newBuilder().setProcess(processId).build())
                    .build());
          } else if (aliveProcesses.contains(processId) && suspectedProcesses.contains(processId)) {
            suspectedProcesses.remove(processId);
            this.pl.enqueue(
                Message.newBuilder()
                    .setType(Type.EPFD_RESTORE)
                    .setEpfdRestore(EpfdRestore.newBuilder().setProcess(processId).build())
                    .build());
          }
          pl.enqueue(
              Message.newBuilder()
                  .setType(Type.PL_SEND)
                  .setPlSend(
                      PlSend.newBuilder()
                          .setMessage(
                              Message.newBuilder()
                                  .setType(Type.EPFD_HEARTBEAT_REQUEST)
                                  .setEpfdHeartbeatRequest(
                                      EpfdHeartbeatRequest_.newBuilder().build())
                                  .build())
                          .setDestination(processId)
                          .build())
                  .setAbstractionId(ABSTRACTION)
                  .build());
        });

    this.aliveProcesses = new HashSet<>();
    this.startTimer = true;
    return true;
  }

  private boolean handleHeartbeatRequest(final Message message) {
    pl.enqueue(
        Message.newBuilder()
            .setType(Type.PL_SEND)
            .setPlSend(
                PlSend.newBuilder()
                    .setMessage(
                        Message.newBuilder()
                            .setType(Type.EPFD_HEARTBEAT_REPLY)
                            .setEpfdHeartbeatReply(EpfdHeartbeatReply_.newBuilder().build())
                            .build())
                    .setDestination(message.getPlDeliver().getSender())
                    .build())
            .setAbstractionId(ABSTRACTION)
            .build());
    return true;
  }

  private boolean handleHeartbeatReply(final Message message) {
    final ProcessId sender = getProcessIdByHost(message.getPlDeliver().getSender().getPort());
    this.aliveProcesses.add(sender);
    return true;
  }

  private Type resolveMessageType(Message message) {
    if (message.getType() == Type.PL_DELIVER) {
      return message.getPlDeliver().getMessage().getType();
    }
    return message.getType();
  }

  private ProcessId getProcessIdByHost(final int port) {
    return processes.stream()
        .filter(processId -> (processId.getPort() == port))
        .findFirst()
        .orElseThrow(
            () ->
                new RuntimeException(
                    String.format(
                        "Didn't find process with host %s and port %s in involved processes list %s",
                        port, this.processes)));
  }

  public Set<ProcessId> getProcesses() {
    return processes;
  }

  public Set<ProcessId> getSuspectedProcesses() {
    return suspectedProcesses;
  }
}
