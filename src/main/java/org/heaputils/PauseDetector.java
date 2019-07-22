package org.heaputils;

import java.util.Date;

/**
* Created with IntelliJ IDEA.
* User: gil
* Date: 4/6/13
* Time: 8:41 AM
* To change this template use File | Settings | File Templates.
*/
class PauseDetector extends Thread {
    long interval;
    long threshold;
    long lastSleepTime;
    boolean doRun;
    Long allocatedLong = new Long(0);

    PauseDetector(long interval, long threshold) {
        this.interval = interval;
        this.threshold = threshold;
        doRun = true;
        this.setDaemon(true);
    }

    public void terminate() {
        doRun = false;
    }

    public void run() {
        lastSleepTime = System.currentTimeMillis();
        while (doRun) {
            long currTime = System.currentTimeMillis();
            if (currTime - lastSleepTime > threshold) {
                System.err.println("\n*** PauseDetector detected a " +
                        (currTime - lastSleepTime) + " ms pause at " +
                        new Date() + " ***\n");
            }
            lastSleepTime = currTime;
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                System.err.println(e.toString());
                System.err.println("(No reason why I should be woken...)");
            }
            allocatedLong = new Long(lastSleepTime + allocatedLong); // Generate one new object.
        }
    }
}
