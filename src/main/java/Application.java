import algorithms.BestEffortBroadcast;
import algorithms.EpochChange;
import algorithms.EventualLeaderDetector;
import algorithms.EventuallyPerfectFailureDetector;
import algorithms.PerfectLink;
import algorithms.UniformConsensus;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import main.Paxos.Message;
import main.Paxos.Message.Type;
import main.Paxos.ProcessId;
import main.Paxos.UcPropose;
import main.Paxos.Value;
import utils.SocketUtils;

public class Application {

  private static final String PROCESS_HOST = "127.0.0.1";
  private static final String OWNER = "ncoman";
  private static final int[] PORTS = {5004, 5005, 5006};
  private static final int[] PROCESS_INDEXES = {1, 2, 3};
  public static String SYSTEM_ID;
  // This Process Identity
  private static int SYSTEM_INDEX;

  public static void main(String[] args)
      throws InterruptedException, ExecutionException, IOException {

    SYSTEM_INDEX = Integer.parseInt(args[0]);

    final Message appProposeMessage =
        SocketUtils.registerProcess(
            PROCESS_HOST, PORTS[SYSTEM_INDEX], PROCESS_INDEXES[SYSTEM_INDEX], OWNER);

    SYSTEM_ID = appProposeMessage.getSystemId();

    final List<ProcessId> involvedProcesses =
        appProposeMessage.getNetworkMessage().getMessage().getAppPropose().getProcessesList();

    // to be used in UC_PROPOSE
    final Value processValue =
        appProposeMessage.getNetworkMessage().getMessage().getAppPropose().getValue();

    final ProcessId currentProcessId = getCurrentProcessId(involvedProcesses);

    final PerfectLink pl = new PerfectLink(SocketUtils.PROCESS_SERVER, currentProcessId, SYSTEM_ID);
    final BestEffortBroadcast beb = new BestEffortBroadcast(pl, involvedProcesses);

    // Start the algorithm by adding the UC_PROPOSE message in the message queue
    pl.enqueue(
        Message.newBuilder()
            .setType(Type.UC_PROPOSE)
            .setUcPropose(UcPropose.newBuilder().setValue(processValue).build())
            .build());

    final EventuallyPerfectFailureDetector epfd =
        new EventuallyPerfectFailureDetector(new HashSet<>(involvedProcesses), 100, pl);
    final EventualLeaderDetector eld = new EventualLeaderDetector(pl, epfd);
    final EpochChange ec = new EpochChange(pl, involvedProcesses, currentProcessId);
    final UniformConsensus uc = new UniformConsensus(involvedProcesses, pl, currentProcessId);

    // listen incoming messages
    pl.startNetworkListener();

    // start processing system messages
    while (true) {
      pl.eventLoop();
    }
  }

  private static ProcessId getCurrentProcessId(final List<ProcessId> involvedProcesses) {
    return involvedProcesses.stream()
        .filter(processId -> processId.getIndex() == PROCESS_INDEXES[SYSTEM_INDEX])
        .findFirst()
        .orElseThrow(
            () ->
                new RuntimeException(
                    String.format(
                        "Process with index %s not registered in hub!",
                        PROCESS_INDEXES[SYSTEM_INDEX])));
  }
}
