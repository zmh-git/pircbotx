/**
 * Copyright (C) 2010-2013 Leon Blakey <lord.quackstar at gmail.com>
 *
 * This file is part of PircBotX.
 *
 * PircBotX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PircBotX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PircBotX. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pircbotx;

import com.google.common.base.Joiner;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import static com.google.common.util.concurrent.Service.State;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.pircbotx.exception.IrcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager that provides an easy way to create bots on many different servers
 * with the same or close to the same information. All important setup methods
 * have been mirrored here. For documentation, see their equivalent PircBotX
 * methods.
 * <p>
 * <b>Note:</b> Setting any value after connectAll() is invoked will NOT update
 * all existing bots. You will need to loop over the bots and call the set methods
 * manually
 * <p/>
 * @author Leon Blakey <lord.quackstar at gmail.com>
 */
@Slf4j
public class MultiBotManager {
	protected static final AtomicInteger managerCount = new AtomicInteger();
	protected final int managerNumber;
	protected final HashMap<PircBotX, ListenableFuture> runningBots = new HashMap();
	protected final BiMap<PircBotX, Integer> runningBotsNumbers = HashBiMap.create();
	protected final Object runningBotsLock = new Object[0];
	protected final ListeningExecutorService botPool;
	//Code for starting
	protected List<PircBotX> startQueue = new ArrayList();
	protected State state = State.NEW;
	protected final Object stateLock = new Object[0];

	public MultiBotManager() {
		managerNumber = managerCount.getAndIncrement();
		ThreadPoolExecutor defaultPool = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		defaultPool.allowCoreThreadTimeOut(true);
		this.botPool = MoreExecutors.listeningDecorator(defaultPool);
	}

	public MultiBotManager(ExecutorService botPool) {
		this.botPool = MoreExecutors.listeningDecorator(botPool);
		this.managerNumber = managerCount.getAndIncrement();
	}

	@Synchronized("stateLock")
	public void addBot(Configuration config) {
		if (state == State.NEW) {
			log.debug("Not started yet, add to queue");
			startQueue.add(new PircBotX(config));
		} else if (state == State.RUNNING) {
			log.debug("Already running, start bot immediately");
			startBot(new PircBotX(config));
		} else
			throw new RuntimeException("MultiBotManager is not running. State: " + state);
	}

	public void start() {
		synchronized (stateLock) {
			if (state != State.NEW)
				throw new RuntimeException("MultiBotManager has already been started. State: " + state);
			state = State.STARTING;
		}

		for (PircBotX bot : startQueue)
			startBot(bot);
		startQueue.clear();

		synchronized (stateLock) {
			state = State.RUNNING;
		}
	}

	protected void startBot(final PircBotX bot) {
		ListenableFuture future = botPool.submit(new BotRunner(bot));
		synchronized (runningBotsLock) {
			runningBots.put(bot, future);
			runningBotsNumbers.put(bot, bot.getBotId());
		}
		Futures.addCallback(future, new BotFutureCallback(bot));
	}

	/**
	 * Disconnect all bots from their respective severs cleanly.
	 */
	public void stop() {
		synchronized (stateLock) {
			if (state != State.RUNNING)
				throw new RuntimeException("MultiBotManager cannot be stopped again or before starting. State: " + state);
			state = State.STOPPING;
		}

		for (PircBotX bot : runningBots.keySet())
			if (bot.isConnected())
				bot.sendIRC().quitServer();

		botPool.shutdown();
	}

	public void stopAndWait() throws InterruptedException {
		stop();

		do
			synchronized (runningBotsLock) {
				log.debug("Waiting 5 seconds for bots [{}] to terminate ", Joiner.on(", ").join(runningBots.values()));
			}
		while (!botPool.awaitTermination(5, TimeUnit.SECONDS));
	}

	/**
	 * Get all the bots that this MultiBotManager is managing. Do not save this
	 * anywhere as it will be out of date when a new bot is created
	 * @return An <i>unmodifiable</i> Set of bots that are being managed
	 */
	public ImmutableSet<PircBotX> getBots() {
		synchronized (runningBots) {
			return ImmutableSet.copyOf(runningBots.keySet());
		}
	}

	@RequiredArgsConstructor
	protected class BotRunner implements Callable<Void> {
		@NonNull
		protected final PircBotX bot;

		public Void call() throws Exception {
			Thread.currentThread().setName("botPool" + managerNumber + "-bot" + bot.getBotId());
			bot.connect();
			return null;
		}
	}

	@RequiredArgsConstructor
	protected class BotFutureCallback implements FutureCallback<Void> {
		protected final Logger log = LoggerFactory.getLogger(getClass());
		@NonNull
		protected final PircBotX bot;

		public void onSuccess(Void result) {
			log.debug("Bot #" + bot.getBotId() + " finished");
			remove();
		}

		public void onFailure(Throwable t) {
			log.error("Bot exited with Exception", t);
			remove();
		}

		protected void remove() {
			synchronized (runningBotsLock) {
				runningBots.remove(bot);
				runningBotsNumbers.remove(bot);

				if (runningBots.isEmpty())
					//Change state to TERMINATED if this is the last but to be removed during shutdown
					if (state == State.STOPPING)
						synchronized (stateLock) {
							if (state == State.STOPPING)
								state = State.TERMINATED;
						}
			}


		}
	}
}
