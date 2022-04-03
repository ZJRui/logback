/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.core;

import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import ch.qos.logback.core.util.InterruptUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This appender and derived classes, log events asynchronously. In order to
 * avoid loss of logging events, this appender should be closed. It is the
 * user's responsibility to close appenders, typically at the end of the
 * application lifecycle.
 * <p>
 * This appender buffers events in a {@link BlockingQueue}. {@link Worker}
 * thread created by this appender takes events from the head of the queue, and
 * dispatches them to the single appender attached to this appender.
 * <p>
 * Please refer to the
 * <a href="http://logback.qos.ch/manual/appenders.html#AsyncAppender">logback
 * manual</a> for further information about this appender.
 * </p>
 *
 * @param <E>
 * @author Ceki G&uuml;lc&uuml;
 * @author Torsten Juergeleit
 * @since 1.0.4
 */
public class AsyncAppenderBase<E> extends UnsynchronizedAppenderBase<E> implements AppenderAttachable<E> {

    AppenderAttachableImpl<E> aai = new AppenderAttachableImpl<E>();
    BlockingQueue<E> blockingQueue;

    /**
     * The default buffer size.
     */
    public static final int DEFAULT_QUEUE_SIZE = 256;
    int queueSize = DEFAULT_QUEUE_SIZE;

    int appenderCount = 0;

    static final int UNDEFINED = -1;
    int discardingThreshold = UNDEFINED;
    boolean neverBlock = false;

    Worker worker = new Worker();

    /**
     * The default maximum queue flush time allowed during appender stop. If the
     * worker takes longer than this time it will exit, discarding any remaining
     * items in the queue
     */
    public static final int DEFAULT_MAX_FLUSH_TIME = 1000;
    int maxFlushTime = DEFAULT_MAX_FLUSH_TIME;

    /**
     * Is the eventObject passed as parameter discardable? The base class's
     * implementation of this method always returns 'false' but sub-classes may (and
     * do) override this method.
     * <p>
     * Note that only if the buffer is nearly full are events discarded. Otherwise,
     * when the buffer is "not full" all events are logged.
     *
     * @param eventObject
     * @return - true if the event can be discarded, false otherwise
     */
    protected boolean isDiscardable(E eventObject) {
        return false;
    }

    /**
     * Pre-process the event prior to queueing. The base class does no
     * pre-processing but sub-classes can override this behavior.
     *
     * @param eventObject
     */
    protected void preprocess(E eventObject) {
    }

    @Override
    public void start() {
        if (isStarted())
            return;
        if (appenderCount == 0) {
            addError("No attached appenders found.");
            return;
        }
        if (queueSize < 1) {
            addError("Invalid queue size [" + queueSize + "]");
            return;
        }
        blockingQueue = new ArrayBlockingQueue<E>(queueSize);

        if (discardingThreshold == UNDEFINED)
            discardingThreshold = queueSize / 5;
        addInfo("Setting discardingThreshold to " + discardingThreshold);
        worker.setDaemon(true);
        worker.setName("AsyncAppender-Worker-" + getName());
        // make sure this instance is marked as "started" before staring the worker
        // Thread
        super.start();
        worker.start();
    }

    @Override
    public void stop() {
        if (!isStarted())
            return;

        // mark this appender as stopped so that Worker can also processPriorToRemoval
        // if it is invoking
        // aii.appendLoopOnAppenders
        // and sub-appenders consume the interruption
        super.stop();

        // interrupt the worker thread so that it can terminate. Note that the
        // interruption can be consumed by sub-appenders
        worker.interrupt();

        InterruptUtil interruptUtil = new InterruptUtil(context);

        try {
            interruptUtil.maskInterruptFlag();

            worker.join(maxFlushTime);

            // check to see if the thread ended and if not add a warning message
            if (worker.isAlive()) {
                addWarn("Max queue flush timeout (" + maxFlushTime + " ms) exceeded. Approximately "
                        + blockingQueue.size() + " queued events were possibly discarded.");
            } else {
                addInfo("Queue flush finished successfully within timeout.");
            }

        } catch (InterruptedException e) {
            int remaining = blockingQueue.size();
            addError("Failed to join worker thread. " + remaining + " queued events may be discarded.", e);
        } finally {
            interruptUtil.unmaskInterruptFlag();
        }
    }

    @Override
    protected void append(E eventObject) {
        if (isQueueBelowDiscardingThreshold() && isDiscardable(eventObject)) {
            return;
        }
        preprocess(eventObject);
        put(eventObject);
    }

    private boolean isQueueBelowDiscardingThreshold() {
        return (blockingQueue.remainingCapacity() < discardingThreshold);
    }

    private void put(E eventObject) {
        if (neverBlock) {
            blockingQueue.offer(eventObject);
        } else {
            putUninterruptibly(eventObject);
        }
    }

    private void putUninterruptibly(E eventObject) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    blockingQueue.put(eventObject);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public int getDiscardingThreshold() {
        return discardingThreshold;
    }

    public void setDiscardingThreshold(int discardingThreshold) {
        this.discardingThreshold = discardingThreshold;
    }

    public int getMaxFlushTime() {
        return maxFlushTime;
    }

    public void setMaxFlushTime(int maxFlushTime) {
        this.maxFlushTime = maxFlushTime;
    }

    /**
     * Returns the number of elements currently in the blocking queue.
     *
     * @return number of elements currently in the queue.
     */
    public int getNumberOfElementsInQueue() {
        return blockingQueue.size();
    }

    public void setNeverBlock(boolean neverBlock) {
        this.neverBlock = neverBlock;
    }

    public boolean isNeverBlock() {
        return neverBlock;
    }

    /**
     * The remaining capacity available in the blocking queue.
     * <p>
     * See also {@link java.util.concurrent.BlockingQueue#remainingCapacity()
     * BlockingQueue#remainingCapacity()}
     *
     * @return the remaining capacity
     * 
     */
    public int getRemainingCapacity() {
        return blockingQueue.remainingCapacity();
    }

    public void addAppender(Appender<E> newAppender) {
        if (appenderCount == 0) {
            appenderCount++;
            addInfo("Attaching appender named [" + newAppender.getName() + "] to AsyncAppender.");
            aai.addAppender(newAppender);
        } else {
            addWarn("One and only one appender may be attached to AsyncAppender.");
            addWarn("Ignoring additional appender named [" + newAppender.getName() + "]");
        }
    }

    public Iterator<Appender<E>> iteratorForAppenders() {
        return aai.iteratorForAppenders();
    }

    public Appender<E> getAppender(String name) {
        return aai.getAppender(name);
    }

    public boolean isAttached(Appender<E> eAppender) {
        return aai.isAttached(eAppender);
    }

    public void detachAndStopAllAppenders() {
        aai.detachAndStopAllAppenders();
    }

    public boolean detachAppender(Appender<E> eAppender) {
        return aai.detachAppender(eAppender);
    }

    public boolean detachAppender(String name) {
        return aai.detachAppender(name);
    }

    class Worker extends Thread {

        public void run() {
            AsyncAppenderBase<E> parent = AsyncAppenderBase.this;
            AppenderAttachableImpl<E> aai = parent.aai;

            /**
             *
             *
             从阻塞队列中取出数据的方式 有三种方式
             获取数据：
             　　poll(time):取走BlockingQueue里排在首位的对象,若不能立即取出,则可以等time参数规定的时间,
             　　　　取不到时返回null;
             　　poll(long timeout, TimeUnit unit)：从BlockingQueue取出一个队首的对象，如果在指定时间内，
             　　　　队列一旦有数据可取，则立即返回队列中的数据。否则知道时间超时还没有数据可取，返回失败。
             　　take():取走BlockingQueue里排在首位的对象,若BlockingQueue为空,阻断进入等待状态直到
             　　　　BlockingQueue有新的数据被加入;
             　　drainTo():一次性从BlockingQueue获取所有可用的数据对象（还可以指定获取数据的个数），
             　　　　通过该方法，可以提升获取数据效率；不需要多次分批加锁或释放锁。

             （1）而drainTo是批量获取，为空不阻塞。 我们想要的结果是为空的时候需要阻塞，不然会一直占用cpu
             （2）尝试将元素添加到集合 c 时遇到的失败可能会导致在引发相关异常时元素既不在集合中，又不属于任何一个集合或两个集合。


             实现批量从队列中取数据且当队列为空的时候 进行阻塞当前线程，如果不阻塞当前线程就会导致当前线程长期占用cpu 是有必要的，有两种实现方案
             （1）logback 先使用take阻塞当前线程，然后drainTo.队列中没有数据导致当前线程长期阻塞，如果想退出的话需要中断当前线程

             （2）Debezium 自己实现了阻塞操作，没有使用take， 当Engine从队列中取不到数据的时候 就for循环sleep，这种方式的好处在于 Engine虽然取不到数据，
             但是可以经常获取到cpu，检查自己的状态。 当mysql binlog线程撕掉的时候 需要通知Engine线程退出，这个时候Engine就能够及时退出。
             不会因为没有数据而一直阻塞导致线程无法退出
             *
             *
             *
             */
            // loop while the parent is started
            while (parent.isStarted()) {
                try {
                    List<E> elements = new ArrayList<E>();
                    E e0 = parent.blockingQueue.take();
                    elements.add(e0);
                    parent.blockingQueue.drainTo(elements);
                    for (E e : elements) {
                        aai.appendLoopOnAppenders(e);
                    }
                } catch (InterruptedException e1) {
                    // exit if interrupted
                    /**
                     * 队列中没有数据导致当前线程长期阻塞，如果想退出的话需要中断当前线程
                     */
                    break;
                }
            }

            addInfo("Worker thread will flush remaining events before exiting. ");

            for (E e : parent.blockingQueue) {
                aai.appendLoopOnAppenders(e);
                parent.blockingQueue.remove(e);
            }

            aai.detachAndStopAllAppenders();
        }
    }
}
