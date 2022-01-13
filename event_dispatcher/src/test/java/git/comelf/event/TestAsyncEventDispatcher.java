/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package git.comelf.event;

import git.comelf.conf.Configuration;
import git.comelf.event.metrics.SimpleEventTypeMetrics;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class TestAsyncEventDispatcher {

    /* This test checks whether dispatcher hangs on close if following two things
     * happen :
     * 1. A thread which was putting event to event queue is interrupted.
     * 2. Event queue is empty on close.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test(timeout=10000)
    public void testDispatcherOnCloseIfQueueEmpty() throws Exception {
        BlockingQueue<Event> eventQueue = spy(new LinkedBlockingQueue<Event>());
        Event event = mock(Event.class);
        doThrow(new InterruptedException()).when(eventQueue).put(event);
        DrainDispatcher disp = new DrainDispatcher(eventQueue);
        disp.init(new Configuration());
        disp.setDrainEventsOnStop();
        disp.start();
        // Wait for event handler thread to start and begin waiting for events.
        disp.waitForEventThreadToWait();
        try {
            disp.getEventHandler().handle(event);
            Assert.fail("Expected YarnRuntimeException");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getCause() instanceof InterruptedException);
        }
        // Queue should be empty and dispatcher should not hang on close
        Assert.assertTrue("Event Queue should have been empty",
                eventQueue.isEmpty());
        disp.close();
    }

    // Test dispatcher should timeout on draining events.
    @Test(timeout=10000)
    public void testDispatchStopOnTimeout() throws Exception {
        BlockingQueue<Event> eventQueue = new LinkedBlockingQueue<Event>();
        eventQueue = spy(eventQueue);
        // simulate dispatcher is not drained.
        when(eventQueue.isEmpty()).thenReturn(false);
        Configuration conf = new Configuration();
        conf.setInt(Configuration.DISPATCHER_DRAIN_EVENTS_TIMEOUT, 2000);
        DrainDispatcher disp = new DrainDispatcher(eventQueue);
        disp.init(conf);
        disp.setDrainEventsOnStop();
        disp.start();
        disp.waitForEventThreadToWait();
        disp.close();

    }

    @SuppressWarnings("rawtypes")
    private static class DummyHandler implements EventHandler<Event> {
        @Override
        public void handle(Event event) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
        }
    }

    private enum DummyType {
        DUMMY
    }

    private static class TestHandler implements EventHandler<Event> {

        private long sleepTime = 1500;

        TestHandler() {
        }

        TestHandler(long sleepTime) {
            this.sleepTime = sleepTime;
        }

        @Override
        public void handle(Event event) {
            try {
                // As long as 10000 events queued
                Thread.sleep(this.sleepTime);
            } catch (InterruptedException e) {
            }
        }
    }

    private enum TestEnum {
        TestEventType, TestEventType2
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatchDummyEvents(Dispatcher disp, int count) {
        for (int i = 0; i < count; i++) {
            Event event = mock(Event.class);
            when(event.getType()).thenReturn(DummyType.DUMMY);
            disp.getEventHandler().handle(event);
        }
    }

    // Test if drain dispatcher drains events on stop.
    @SuppressWarnings({"rawtypes"})
    @Test(timeout = 10000)
    public void testDrainDispatcherDrainEventsOnStop() throws Exception {
        BlockingQueue<Event> queue = new LinkedBlockingQueue<Event>();
        DrainDispatcher disp = new DrainDispatcher(queue);
        disp.init(new Configuration());
        disp.register(DummyType.class, new DummyHandler());
        disp.setDrainEventsOnStop();
        disp.start();
        disp.waitForEventThreadToWait();
        dispatchDummyEvents(disp, 2);
        disp.close();
        assertEquals(0, queue.size());
    }

    //Test print dispatcher details when the blocking queue is heavy
    @Test(timeout = 10000)
    public void testPrintDispatcherEventDetails() throws Exception {
        Logger log = mock(Logger.class);
        AsyncDispatcher dispatcher = new AsyncDispatcher();
        dispatcher.init(new Configuration());

        Field logger = AsyncDispatcher.class.getDeclaredField("LOG");
        logger.setAccessible(true);
        Field modifiers = Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        modifiers.setInt(logger, logger.getModifiers() & ~Modifier.FINAL);
        Object oldLog = logger.get(null);

        try {
            logger.set(null, log);
            dispatcher.register(TestEnum.class, new TestHandler());
            dispatcher.start();

            for (int i = 0; i < 10000; ++i) {
                Event event = mock(Event.class);
                when(event.getType()).thenReturn(TestEnum.TestEventType);
                dispatcher.getEventHandler().handle(event);
            }
            Thread.sleep(2000);
            //Make sure more than one event to take
            verify(log, atLeastOnce()).
                    info("Latest dispatch event type: TestEventType");
        } finally {
            //... restore logger object
            logger.set(null, oldLog);
            dispatcher.stop();
        }
    }

    //Test print dispatcher details when the blocking queue is heavy
    @Test(timeout = 60000)
    public void testPrintDispatcherEventDetailsAvoidDeadLoop() throws Exception {
        for (int i = 0; i < 5; i++) {
            testPrintDispatcherEventDetailsAvoidDeadLoopInternal();
        }
    }

    public void testPrintDispatcherEventDetailsAvoidDeadLoopInternal()
            throws Exception {
        Logger log = mock(Logger.class);
        AsyncDispatcher dispatcher = new AsyncDispatcher();
        dispatcher.init(new Configuration());

        Field logger = AsyncDispatcher.class.getDeclaredField("LOG");
        logger.setAccessible(true);
        Field modifiers = Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        modifiers.setInt(logger, logger.getModifiers() & ~Modifier.FINAL);
        Object oldLog = logger.get(null);

        try {
            logger.set(null, log);
            dispatcher.register(TestEnum.class, new TestHandler(0));
            dispatcher.start();

            for (int i = 0; i < 10000; ++i) {
                Event event = mock(Event.class);
                when(event.getType()).thenReturn(TestEnum.TestEventType);
                dispatcher.getEventHandler().handle(event);
            }
            Thread.sleep(3000);
        } finally {
            //... restore logger object
            logger.set(null, oldLog);
            dispatcher.stop();
        }
    }

    @Test
    public void testMetricsForDispatcher() throws Exception {
        AsyncDispatcher dispatcher = null;

        try {
            dispatcher = new AsyncDispatcher("RM Event dispatcher");

            SimpleEventTypeMetrics simpleEventTypeMetrics = new SimpleEventTypeMetrics(TestEnum.class);

            // We can the metrics enabled for TestEnum
            dispatcher.addMetrics(simpleEventTypeMetrics, simpleEventTypeMetrics.getEnumClass());
            dispatcher.init(new Configuration());

            // Register handler
            dispatcher.register(TestEnum.class, new TestHandler());
            dispatcher.start();

            for (int i = 0; i < 3; ++i) {
                Event event = mock(Event.class);
                when(event.getType()).thenReturn(TestEnum.TestEventType);
                dispatcher.getEventHandler().handle(event);
            }

            for (int i = 0; i < 2; ++i) {
                Event event = mock(Event.class);
                when(event.getType()).thenReturn(TestEnum.TestEventType2);
                dispatcher.getEventHandler().handle(event);
            }

            // Check event type count.
            GenericTestUtils.waitFor(() -> simpleEventTypeMetrics.get(TestEnum.TestEventType) == 3, 1000, 10000);

            GenericTestUtils.waitFor(() -> simpleEventTypeMetrics.get(TestEnum.TestEventType2) == 2, 1000, 10000);

            // Check time spend.
            Assert.assertTrue(simpleEventTypeMetrics.getTotalProcessingTime(TestEnum.TestEventType) >= 1500 * 3);
            Assert.assertTrue(simpleEventTypeMetrics.getTotalProcessingTime(TestEnum.TestEventType) < 1500 * 4);

            Assert.assertTrue(simpleEventTypeMetrics.getTotalProcessingTime(TestEnum.TestEventType2) >= 1500 * 2);
            Assert.assertTrue(simpleEventTypeMetrics.getTotalProcessingTime(TestEnum.TestEventType2) < 1500 * 3);

            // Make sure metrics consistent.
            Assert.assertEquals(simpleEventTypeMetrics.get(TestEnum.TestEventType), 3);
            Assert.assertEquals(simpleEventTypeMetrics.get(TestEnum.TestEventType2), 2);

        } finally {
            dispatcher.close();
        }

    }


}
