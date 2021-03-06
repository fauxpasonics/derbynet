package org.jeffpiazza.derby.devices;

import java.util.ArrayList;
import jssc.*;
import org.jeffpiazza.derby.Message;
import org.jeffpiazza.derby.serialport.SerialPortWrapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// This class supports the "Derby Timer" device, http://derbytimer.com
public class DerbyTimerDevice extends TimerDeviceTypical {

  public DerbyTimerDevice(SerialPortWrapper portWrapper) {
    super(portWrapper);

    // Once started, we expect a race result within 10 seconds; we allow an
    // extra second before considering the results overdue.
    rsm.setMaxRunningTimeLimit(11000);
  }

  public static String toHumanString() {
    return "Derby Timer";
  }

  private int laneCount = 0;
  // These get initialized upon the start of each new race.
  private int nresults = 0;
  private ArrayList<Message.LaneResult> results;

  // R => 'RESET' crlf 'READY <n> LANES' crlf
  private static final String RESET_COMMAND = "R";
  private static final String LANE_MASK = "M";
  // C => 'OK' crlf
  private static final String CLEAR_LANE_MASK = "C";

  // Response to a "G" is either "U" (up, or closed) or "D" (down, or open)
  private static final String READ_START_SWITCH = "G";

  public boolean probe() throws SerialPortException {
    if (!portWrapper.setPortParams(SerialPort.BAUDRATE_9600,
                                   SerialPort.DATABITS_8,
                                   SerialPort.STOPBITS_1,
                                   SerialPort.PARITY_NONE,
                                   /* rts */ true,
                                   /* dtr */ true)) {
      return false;
    }

    portWrapper.write(RESET_COMMAND);
    long deadline = System.currentTimeMillis() + 2000;
    String s;
    while ((s = portWrapper.next(deadline)) != null) {
      if (s.equals("RESET")) {
        s = portWrapper.next(deadline);
        Matcher m = readyNLanesPattern.matcher(s);
        if (m.find()) {
          laneCount = Integer.parseInt(m.group(1));
        }

        setUp();
        return true;
      }
    }

    return false;
  }

  private static final Pattern readyNLanesPattern = Pattern.compile(
      "^READY\\s*(\\d+)\\s+LANES");
  private static final Pattern singleLanePattern = Pattern.compile(
      "^\\s*(\\d)\\s+(\\d\\.\\d+)(\\s.*|)");

  protected void setUp() {
    portWrapper.registerDetector(new SerialPortWrapper.Detector() {
      @Override
      public String apply(String line) throws SerialPortException {
        if (line.equals("RACE")) {
          if (getGateIsClosed()) {
            setGateIsClosed(false);
            rsm.onEvent(RacingStateMachine.Event.GATE_OPENED,
                        DerbyTimerDevice.this);
          }
          return "";
        } else if (line.equals("FINISH")) {
          if (results != null) {
            raceFinished((Message.LaneResult[]) results.toArray(
                new Message.LaneResult[results.size()]));
            results = null;
            nresults = 0;
          }
          return "";
        } else {
          Matcher m = readyNLanesPattern.matcher(line);
          if (m.find()) {
            int nlanes = Integer.parseInt(m.group(1));
            // If any lanes have been masked, not sure what READY n LANES
            // will report, so only update a larger laneCount.
            if (nlanes > laneCount) {
              laneCount = nlanes;
            }
            if (!getGateIsClosed()) {
              setGateIsClosed(true);
              rsm.onEvent(RacingStateMachine.Event.GATE_CLOSED,
                          DerbyTimerDevice.this);
            }
            return "";
          }
          m = singleLanePattern.matcher(line);
          if (m.find()) {
            int lane = Integer.parseInt(m.group(1));
            String time = m.group(2);
            if (results != null) {
              TimerDeviceUtils.addOneLaneResult(lane, time, nresults, results);
            }
            ++nresults;
            return "";
          }
          return line;
        }
      }
    });
  }

  // TODO synchronized?
  public synchronized void prepareHeat(int roundid, int heat, int lanemask)
      throws SerialPortException {
    RacingStateMachine.State state = rsm.state(this);
    // TODO This isn't necessary if the server won't send a redundant heat-ready
    // No need to bother doing anything if we're already prepared for this heat.
    if (this.roundid == roundid && this.heat == heat
        && (state == RacingStateMachine.State.MARK
            || state == RacingStateMachine.State.SET)) {
      portWrapper.logWriter().traceInternal("Ignoring redundant prepareHeat()");  // TODO
      return;
    }

    prepare(roundid, heat);
    nresults = 0;
    results = new ArrayList<Message.LaneResult>();

    portWrapper.writeAndDrainResponse(CLEAR_LANE_MASK);

    StringBuilder sb = new StringBuilder("Heat prepared: ");
    for (int lane = 0; lane < laneCount; ++lane) {
      if ((lanemask & (1 << lane)) != 0) {
        sb.append(lane + 1);
      } else {
        sb.append("-");
        // Response is "MASKING LANE <n>"
        portWrapper.writeAndDrainResponse(
            LANE_MASK + (char) ('1' + lane), 1, 500);
      }
    }
    portWrapper.logWriter().serialPortLogInternal(sb.toString());
    rsm.onEvent(RacingStateMachine.Event.PREPARE_HEAT_RECEIVED, this);
    if (getGateIsClosed()) {
      rsm.onEvent(RacingStateMachine.Event.GATE_CLOSED, this);
    }
  }

  // Interrogates the starting gate's state.
  @Override
  protected synchronized boolean interrogateGateIsClosed()
      throws NoResponseException, SerialPortException, LostConnectionException {
    portWrapper.write(READ_START_SWITCH);
    long deadline = System.currentTimeMillis() + 1000;
    String s;
    while ((s = portWrapper.next(deadline)) != null) {
      if (s.trim().equals("U")) {
        return true;
      } else if (s.trim().equals("D")) {
        return false;
      } else {
        portWrapper.logWriter().serialPortLogInternal(
            "Unrecognized response: '" + s + "'");
      }
    }

    throw new NoResponseException();
  }

  public int getNumberOfLanes() throws SerialPortException {
    return laneCount;
  }

  @Override
  public void onTransition(RacingStateMachine.State oldState,
                           RacingStateMachine.State newState) {
    if (newState == RacingStateMachine.State.RESULTS_OVERDUE) {
      logOverdueResults();
    }
  }

  protected void whileInState(RacingStateMachine.State state)
      throws SerialPortException, LostConnectionException {
    if (state == RacingStateMachine.State.RESULTS_OVERDUE) {
      // A reasonably common scenario is this: if the gate opens accidentally
      // after the PREPARE_HEAT, the timer starts but there are no cars to
      // trigger a result.
      //
      // updateGateIsClosed() may throw a LostConnectionException if the
      // timer has become unresponsive; otherwise, we'll deal with an
      // unexpected gate closure (which has no real effect).
      if (updateGateIsClosed()) {
        // It can certainly happen that the gate gets closed while the race
        // is running.
        rsm.onEvent(RacingStateMachine.Event.GATE_CLOSED, this);
      }
      // This forces the state machine back to IDLE.
      rsm.onEvent(RacingStateMachine.Event.RESULTS_RECEIVED, this);
      // TODO invokeMalfunctionCallback(false,
      //                                "No result received from last heat.");
      // We'd like to alert the operator to intervene manually, but
      // as currently implemented, a malfunction(false) message would require
      // unplugging/replugging the timer to reset: too invasive.
      portWrapper.logWriter().serialPortLogInternal(
          "No result from timer for the running race; giving up.");
    }
  }
}
