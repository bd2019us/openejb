/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.async;

import org.apache.openejb.AppContext;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.loader.Options;
import org.apache.openejb.util.DaemonThreadFactory;
import org.apache.openejb.util.Duration;

import javax.ejb.EJBException;
import javax.ejb.NoSuchEJBException;
import java.rmi.NoSuchObjectException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @version $Rev$ $Date$
 */
public class AsynchronousPool {

    private final BlockingQueue<Runnable> blockingQueue;
    private final ExecutorService executor;

    public AsynchronousPool(String id, int corePoolSize, int maximumPoolSize, Duration keepAliveTime) {
        this.blockingQueue = new LinkedBlockingQueue<Runnable>();
        this.executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime.getTime(),
                keepAliveTime.getUnit(), blockingQueue, new DaemonThreadFactory("@Asynchronous", id));
    }

    public static AsynchronousPool create(AppContext appContext) {
        final Options options = appContext.getOptions();

        final String id = appContext.getId();
        final int corePoolSize = options.get("AsynchronousPool.CorePoolSize", 10);
        final int maximumPoolSize = options.get("AsynchronousPool.MaximumPoolSize", 20);
        final Duration keepAliveTime = options.get("AsynchronousPool.KeepAliveTime", new Duration(60, TimeUnit.SECONDS));

        return new AsynchronousPool(id, corePoolSize, maximumPoolSize, keepAliveTime);
    }

    public Object invoke(Callable<Object> callable, boolean isVoid) throws Throwable {
        final AtomicBoolean asynchronousCancelled = new AtomicBoolean(false);

        try {

            final Future<Object> future = executor.submit(new AsynchronousCall(callable, asynchronousCancelled));

            if (isVoid) return null;

            return new FutureAdapter<Object>(future, asynchronousCancelled);
        } catch (RejectedExecutionException e) {
            throw new EJBException("fail to allocate internal resource to execute the target task", e);
        }
    }

    private class AsynchronousCall implements Callable<Object> {

        private final Callable<Object> callable;

        private final AtomicBoolean asynchronousCancelled;

        private AsynchronousCall(Callable<Object> callable, AtomicBoolean asynchronousCancelled) {
            this.callable = callable;
            this.asynchronousCancelled = asynchronousCancelled;
        }

        @Override
        public Object call() throws Exception {
            try {
                ThreadContext.initAsynchronousCancelled(asynchronousCancelled);

                final Object value = callable.call();

                if (value instanceof Future<?>) {
                    // This is the Future object returned by the bean code
                    final Future<?> future = (Future<?>) value;

                    return future.get();

                } else {

                    return null;
                }
            } finally {
                ThreadContext.removeAsynchronousCancelled();
            }
        }
    }

    private class FutureAdapter<T> implements Future<T> {

        private final Future<T> target;

        private final AtomicBoolean asynchronousCancelled;

        private volatile boolean canceled;

        public FutureAdapter(Future<T> target, AtomicBoolean asynchronousCancelled) {
            this.target = target;
            this.asynchronousCancelled = asynchronousCancelled;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            /*In EJB 3.1 spec 3.4.8.1.1
             *a. If a client calls cancel on its Future object, the container will attempt to cancel the associated asynchronous invocation only if that invocation has not already been dispatched.
             *  There is no guarantee that an asynchronous invocation can be cancelled, regardless of how quickly cancel is called after the client receives its Future object.
             *  If the asynchronous invocation can not be cancelled, the method must return false.
             *  If the asynchronous invocation is successfully cancelled, the method must return true.
             *b. the meaning of parameter mayInterruptIfRunning is changed.
             *  So, we should never call cancel(true), or the underlying Future object will try to interrupt the target thread.
            */
            /**
             * We use our own flag canceled to identify whether the task is canceled successfully.
             */
            if (canceled) {
                return true;
            }
            if (blockingQueue.remove((Runnable) target)) {
                //We successfully remove the task from the queue
                canceled = true;
                return true;
            } else {
                //Not find the task in the queue, the status might be ran/canceled or running
                //Future.isDone() will return true when the task has been ran or canceled,
                //since we never call the Future.cancel method, the isDone method will only return true when the task has ran
                if (!target.isDone()) {
                    //The task is in the running state
                    asynchronousCancelled.set(mayInterruptIfRunning);
                }
                return false;
            }
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            if (canceled) {
                throw new CancellationException();
            }

            T object = null;

            try {
                object = target.get();
            } catch (Throwable e) {
                handleException(e);
            }

            return object;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (canceled) {
                throw new CancellationException();
            }

            T object = null;

            try {
                object = target.get(timeout, unit);
            } catch (Throwable e) {
                handleException(e);
            }

            return object;

        }

        private void handleException(Throwable e) throws ExecutionException {

            //unwarp the exception to find the root cause
            while (e.getCause() != null) {
                e = (Throwable) e.getCause();
            }

            /*
             * StatefulContainer.obtainInstance(Object, ThreadContext, Method)
             * will return NoSuchObjectException instead of NoSuchEJBException             *
             * when it can't obtain an instance.   Actually, the async client
             * is expecting a NoSuchEJBException.  Wrap it here as a workaround.
             */
            if (e instanceof NoSuchObjectException) {
                e = new NoSuchEJBException(e.getMessage(), (Exception) e);
            }

            boolean isExceptionUnchecked = (e instanceof Error) || (e instanceof RuntimeException);

            // throw checked excpetion and EJBException directly.
            if (!isExceptionUnchecked || e instanceof EJBException) {
                throw new ExecutionException(e);
            }

            // wrap unchecked exception with EJBException before throwing.
            throw (e instanceof Exception) ? new ExecutionException(new EJBException((Exception) e))
                    : new ExecutionException(new EJBException(new Exception(e)));

        }

        @Override
        public boolean isCancelled() {
            return canceled;
        }

        @Override
        public boolean isDone() {
            if (canceled) {
                return false;
            }
            return target.isDone();
        }
    }
}
