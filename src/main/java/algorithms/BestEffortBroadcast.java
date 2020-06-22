package algorithms;

import static main.Paxos.Message.Type.BEB_DELIVER;
import static main.Paxos.Message.Type.PL_DELIVER;
import static main.Paxos.Message.Type.PL_SEND;

import java.util.List;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import main.Paxos.BebDeliver;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.PlSend;
import main.Paxos.ProcessId;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class BestEffortBroadcast {

  private static final String ABSTRACTION = "beb";
  final PerfectLink pl;
  final List<ProcessId> involvedProcesses;

  public BestEffortBroadcast(final PerfectLink pl, final List<ProcessId> involvedProcesses) {
    this.pl = pl;
    this.involvedProcesses = involvedProcesses;
    pl.addHandlerIfNotPresent(this::handle, ABSTRACTION);
  }

  private boolean handle(final Message message) {
    final Message.Type messageType = resolveMessageType(message);
    switch (messageType) {
      case BEB_BROADCAST:
        return handleBroadcast(message);
      case PL_DELIVER:
        return handlePlDeliver(message);
      default:
        return false;
    }
  }

  private boolean handleBroadcast(final Message message) {
    involvedProcesses.forEach(
        processId ->
            pl.enqueue(
                Message.newBuilder()
                    .setType(PL_SEND)
                    .setPlSend(
                        PlSend.newBuilder()
                            .setDestination(processId)
                            .setMessage(message.getBebBroadcast().getMessage())
                            .build())
                    .setAbstractionId(ABSTRACTION)
                    .build()));
    return true;
  }

  private boolean handlePlDeliver(final Message message) {
    if (message.getAbstractionId().equalsIgnoreCase("beb")) {
      pl.enqueue(
          Message.newBuilder()
              .setType(BEB_DELIVER)
              .setBebDeliver(
                  BebDeliver.newBuilder()
                      .setSender(message.getPlDeliver().getSender())
                      .setMessage(message.getPlDeliver().getMessage())
                      .build())
              .build());
      return true;
    }
    return false;
  }

  private Type resolveMessageType(final Message message) {
    return message.getType();
  }
}
