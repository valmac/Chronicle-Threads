/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.ExceptionKey;
import net.openhft.chronicle.core.threads.*;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertFalse;

public class EventGroupTest {
    private List<TestHandler> handlers;
    private ThreadDump threadDump;
    private Map<ExceptionKey, Integer> exceptions;

    @Before
    public void handlersInit() {
        handlers = new ArrayList<>();
    }

    @After
    public void checkHandlersClosed() {
        handlers.forEach(TestHandler::checkCloseOrder);
    }

    @Before
    public void threadDump() {
        threadDump = new ThreadDump();
    }

    @After
    public void checkThreadDump() {
        threadDump.assertNoNewThreads();
    }

    @Before
    public void recordExceptions() {
        exceptions = Jvm.recordExceptions();
    }

    @After
    public void checkExceptions() {
        if (Jvm.hasException(exceptions)) {
            Jvm.dumpException(exceptions);
            Jvm.resetExceptionHandlers();
            Assert.fail();
        }
    }

    @Test
    public void testSimpleEventGroupTest() throws InterruptedException {

        final AtomicInteger value = new AtomicInteger();

        Thread t;
        try (final EventLoop eventGroup = new EventGroup(true)) {
            eventGroup.start();
            t = new Thread(eventGroup::awaitTermination);
            t.start();
            eventGroup.addHandler(() -> {
                if (value.get() == 10)
                    // throw this if you don't wish to be called back
                    throw new InvalidEventHandlerException();
                value.incrementAndGet();
                return true;
            });

            final long start = System.currentTimeMillis();
            while (value.get() != 10) {
                Jvm.pause(10);
            }

            Assert.assertTrue(System.currentTimeMillis() < start + TimeUnit.SECONDS.toMillis(5));

            for (int i = 0; i < 10; i++) {
                Assert.assertEquals(10, value.get());
                Jvm.pause(1);
            }
        }
        t.join(100);
        assertFalse(t.isAlive());
    }

    @Test(timeout = 1000)
    public void testCloseAwaitTermination() throws InterruptedException {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.start();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 1000)
    public void testCloseStopAwaitTermination() throws InterruptedException {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.start();
        eventGroup.stop();
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test(timeout = 1000)
    public void testCloseAwaitTerminationWithoutStarting() throws InterruptedException {
        final EventLoop eventGroup = new EventGroup(true);
        eventGroup.close();
        eventGroup.awaitTermination();
    }

    @Test
    public void checkNoThreadsCreatedIfEventGroupNotStarted() throws InterruptedException {
        final ThreadDump threadDump = new ThreadDump();
        try (final EventLoop eventGroup = new EventGroup(true)) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            threadDump.assertNoNewThreads();
        }
    }

    @Test
    public void checkAllEventHandlerTypesStartAndStop() throws InterruptedException {
        try (final EventLoop eventGroup = new EventGroup(true)) {
            for (HandlerPriority hp : HandlerPriority.values())
                eventGroup.addHandler(new EventGroupTest.TestHandler(hp));
            eventGroup.start();
            for (TestHandler handler : this.handlers)
                handler.started.await(100, TimeUnit.MILLISECONDS);
        }
    }

    @Test(timeout = 5000)
    public void testBlockingHandlersOnlyStartWhenStarted() throws InterruptedException {
        checkOnlyStartWhenStartedTwice(HandlerPriority.BLOCKING);
    }

    @Test(timeout = 5000)
    public void testReplicationHandlersOnlyStartWhenStarted() throws InterruptedException {
        checkOnlyStartWhenStarted(HandlerPriority.REPLICATION);
    }

    @Test(timeout = 5000)
    public void testConcurrentHandlers() throws InterruptedException {
        checkOnlyStartWhenStartedTwice(HandlerPriority.CONCURRENT);
    }

    private void checkOnlyStartWhenStartedTwice(HandlerPriority priority) throws InterruptedException {
        try (final EventLoop eventGroup = new EventGroup(true)) {
            TestHandler handler = new TestHandler(priority, 1);
            eventGroup.addHandler(handler);
            Jvm.pause(100);
            Assert.assertEquals(1, handler.started.getCount());
            eventGroup.start();
            handler.started.await(100, TimeUnit.MILLISECONDS);
            // add a new one after start and ensure it runs
            TestHandler handler2 = new TestHandler(HandlerPriority.CONCURRENT, 2);
            eventGroup.addHandler(handler2);
            handler2.started.await(100, TimeUnit.MILLISECONDS);
        }
    }

    private void checkOnlyStartWhenStarted(HandlerPriority priority) throws InterruptedException {
        try (final EventLoop eventGroup = new EventGroup(true)) {
            TestHandler handler = new TestHandler(priority);
            eventGroup.addHandler(handler);
            Jvm.pause(100);
            Assert.assertEquals(1, handler.started.getCount());
            eventGroup.start();
            handler.started.await(100, TimeUnit.MILLISECONDS);
        }
    }

    class TestHandler implements EventHandler, Closeable {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicLong loopFinishedNS = new AtomicLong();
        final AtomicLong closedNS = new AtomicLong();
        final HandlerPriority priority;
        final int hash;

        TestHandler(HandlerPriority priority) {
            this(priority, 0);
        }

        TestHandler(HandlerPriority priority, int hash) {
            this.priority = priority;
            this.hash = hash;
            handlers.add(this);
        }

        @Override
        public boolean action() throws InvalidEventHandlerException, InterruptedException {
            started.countDown();
            if (priority == HandlerPriority.BLOCKING)
                LockSupport.park();
            return false;
        }

        @NotNull
        @Override
        public HandlerPriority priority() {
            return priority;
        }

        @Override
        public void loopFinished() {
            Assert.assertTrue("loopFinished called once only "+this, loopFinishedNS.compareAndSet(0, System.nanoTime()));
            Jvm.busyWaitMicros(1);
        }

        @Override
        public void close() throws IOException {
            Assert.assertTrue("close called once only "+this, closedNS.compareAndSet(0, System.nanoTime()));
        }

        void checkCloseOrder() {
            Assert.assertTrue(this.toString(), loopFinishedNS.get() != 0);
            Assert.assertTrue(this.toString(), closedNS.get() != 0);
            Assert.assertTrue(this.toString(), loopFinishedNS.get() < closedNS.get());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return "TestHandler{" +
                    "priority=" + priority +
                    ", loopFinishedNS=" + loopFinishedNS +
                    ", closedNS=" + closedNS +
                    ", started=" + started.getCount() +
                    ", hash=" + hash +
                    '}';
        }
    }
}
