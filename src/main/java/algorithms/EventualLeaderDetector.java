package algorithms;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.util.Comparator;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos.EldTrust;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.ProcessId;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class EventualLeaderDetector {

  private static final String ABSTRACTION = "eld";

  final PerfectLink pl;
  final EventuallyPerfectFailureDetector epfd;
  volatile ProcessId leader;

  public EventualLeaderDetector(final PerfectLink pl, final EventuallyPerfectFailureDetector epfd) {
    this.pl = pl;
    this.epfd = epfd;
    leader = null;
    this.pl.addHandlerIfNotPresent(this::handle, ABSTRACTION);
    final Runnable leaderDetection =
        () -> {
          while (true) {
            final ProcessId possibleLeader = getMaxRank();
            if (nonNull(possibleLeader)
                && (isNull(leader) || leader.getRank() != possibleLeader.getRank())) {
              leader = possibleLeader;
              pl.enqueue(
                  Message.newBuilder()
                      .setType(Type.ELD_TRUST)
                      .setEldTrust(EldTrust.newBuilder().setProcess(leader).build())
                      .build());
            }
          }
        };
    new Thread(leaderDetection).start();
  }

  private boolean handle(final Message message) {
    final Message.Type messageType = resolveMessageType(message);
    switch (messageType) {
      case EPFD_SUSPECT:
        return handleEpfdSuspect(message);
      case EPFD_RESTORE:
        return handleEpfdRestore(message);
      default:
        return false;
    }
  }

  private boolean handleEpfdRestore(final Message message) {
    this.epfd.getSuspectedProcesses().remove(message.getEpfdRestore().getProcess());
    return true;
  }

  private boolean handleEpfdSuspect(final Message message) {
    this.epfd.getSuspectedProcesses().add(message.getEpfdSuspect().getProcess());
    return true;
  }

  private Type resolveMessageType(Message message) {
    if (message.getType() == Type.PL_DELIVER) {
      return message.getPlDeliver().getMessage().getType();
    }
    return message.getType();
  }

  private ProcessId getMaxRank() {

    return this.epfd.getProcesses().stream()
        .filter(processId -> !this.epfd.getSuspectedProcesses().contains(processId))
        .max(Comparator.comparing(ProcessId::getRank))
        .orElse(null);
  }
}
