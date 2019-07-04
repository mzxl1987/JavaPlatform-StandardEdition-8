/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW          = 0;                                  // 新建状态
    private static final int COMPLETING   = 1;                                  // 完成中
    private static final int NORMAL       = 2;                                  // 执行结束
    private static final int EXCEPTIONAL  = 3;                                  // 抛出异常
    private static final int CANCELLED    = 4;                                  // 取消
    private static final int INTERRUPTING = 5;                                  // 中断中
    private static final int INTERRUPTED  = 6;                                  // 中断完成

    /** The underlying callable; nulled out after running */
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    private Object outcome; // non-volatile, protected by state reads/writes    // 输出结果
    /** The thread running the callable; CASed during run() */
    private volatile Thread runner;                                             // 执行线程
    /** Treiber stack of waiting threads */
    private volatile WaitNode waiters;                                          // 等待的节点

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL)
            return (V)x;                                                         // 返回结果
        if (s >= CANCELLED)
            throw new CancellationException();
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable                  // 创建新状态
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {                              
        this.callable = Executors.callable(runnable, result);                     // runnable加入线程池
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;                                                // 只要>= CANCELLED就是取消
    }

    public boolean isDone() {
        return state != NEW;                                                      // 只要不是NEW 就是Done
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&                                                     // state is NEW
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,                    // state 设置为 INTERRUPTING / CANCELLED 失败
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;                                                         // 取消失败
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {                                          // state 修改成功, 判断是否需要中断
                try {
                    Thread t = runner;                                            // 获取 thread
                    if (t != null)
                        t.interrupt();                                            // thread不为空的时, 执行中断
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);         // 最终将state 修改为 INTERRUPTED
                }
            }
        } finally {
            finishCompletion();                                                    // 完成
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        if (s <= COMPLETING)                                                      // 尚未完成
            s = awaitDone(false, 0L);                                             // 等待, 至完成
        return report(s);                                                         // 返回结果 
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)                                                        // unit不可以为 null, 
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&                                                   // 尚未完成
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)          // 且等待超时,尚未完成
            throw new TimeoutException();                                        // 抛出超时异常
        return report(s);                                                        // 返回结果
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {   // 设置 state 由 NEW to COMPLETING
            outcome = v;                                                      // 给outcome赋值
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state   // 设置state to NORMAL, 意味着结束
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {   // 设置 state from NEW to COMPLETING
            outcome = t;                                                      // 赋值给 outcome
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state // 设置 state 为 EXCEPTIONAL
            finishCompletion();
        }
    }

    public void run() {
        if (state != NEW ||                                                   // state 不为 NEW
            !UNSAFE.compareAndSwapObject(this, runnerOffset,                  // runner 不为 null
                                         null, Thread.currentThread()))
            return;                                                            // 返回,表示已经执行过run()
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {                                   // state 为 NEW, callable不为 null
                V result;
                boolean ran;
                try {
                    result = c.call();                                         // 调用call(),并返回result
                    ran = true;                                                // 设置ran为 true
                } catch (Throwable ex) {
                    result = null;
                    ran = false;                                               // 设置ran为 false
                    setException(ex);                                          // 设置返回结果为ex,并修改state为 INTERRUPTING
                }
                if (ran)
                    set(result);                                               // 设置返回结果,并修改state为 NORMAL
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)                                             // 如果state为 INTERRUPTING, 执行相应的处理
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {                                        // 等待的节点, 是一个链表
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {                                          // 当前线程已结束
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null;) {                             // 获取等待的节点
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {   // 将waiters设置为null
                for (;;) {                                                     // 该循环就是唤醒waiters中的thread
                    Thread t = q.thread;                                       // 获取waiter的 thread
                    if (t != null) {
                        q.thread = null;                                       // 将q.thread引用设置为空
                        LockSupport.unpark(t);                                 // 唤醒线程
                    }
                    WaitNode next = q.next;                                    // 获取下一个waiterNode
                    if (next == null)                                          // 如果next is null,终止
                        break;
                    q.next = null; // unlink to help gc                        // 将 q.next设置为null, 此时waitNode没有引用, 则利于GC回收
                    q = next;                                                  // 将 q 引用至 next
                }
                break;
            }
        }

        done();                                                                // 完成

        callable = null;        // to reduce footprint                         // callable 置空
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;            // 设置deadline
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            if (Thread.interrupted()) {                                          // 如果线程已中断
                removeWaiter(q);                                                 // 删除节点
                throw new InterruptedException();                                // 抛出异常
            }

            int s = state;
            if (s > COMPLETING) {                                                // 已完成
                if (q != null) 
                    q.thread = null;                                             // thread置 null
                return s;                                                        // 返回state
            }
            else if (s == COMPLETING) // cannot time out yet                     // 正在执行
                Thread.yield();                                                  // 让出CPU, 重新CPU, 提高整体吞吐量
            else if (q == null)                                                  
                q = new WaitNode();                                              // 如果waitNode为 null, 创建一个新的waitNode
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,        // 重新赋值waiters,
                                                     q.next = waiters, q);       // q.next = waiters, waiters = q
            else if (timed) {                                                    // 设置延时
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {                                               // 超时
                    removeWaiter(q);                                             // 删除waiter
                    return state;                                                // 返回结果
                }
                LockSupport.parkNanos(this, nanos);                              // block currentThread for nanos nanosecond
            }
            else
                LockSupport.park(this);                                          // block currentThread
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;                                               // 记住nextWaiterNode
                    if (q.thread != null)
                        pred = q;                                             // 记住preWaiterNode
                    else if (pred != null) {                                  // q.thread == null && preWaiterNode != null
                        pred.next = s;                                        // 将pred.next = pred.next.next
                        if (pred.thread == null) // check for race            // 如果pred.thread == null
                            continue retry;                                   // 重新检测, 继续remove
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset, // 如果CAS失败, 重新开始
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset                            // 获取 state的偏移量
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset                           // 获取 runner的偏移量
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset                          // 获取 waiters的偏移量
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
