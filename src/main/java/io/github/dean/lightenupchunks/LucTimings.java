package io.github.dean.lightenupchunks;

import java.util.concurrent.atomic.LongAdder;

/** Cumulative stage timers (nanoseconds) for one relight task; logged on completion to compare before/after optimisations. */
public final class LucTimings {
	public static final LongAdder SUBMIT = new LongAdder();
	public static final LongAdder PROCESS = new LongAdder();
	public static final LongAdder FLUSH = new LongAdder();
	public static final LongAdder PREFLIGHT = new LongAdder(); // async worker CPU time, summed across threads
	public static final LongAdder SAVE = new LongAdder();
	public static final LongAdder BROADCAST = new LongAdder();
	public static final LongAdder VOXY = new LongAdder();
	public static final LongAdder RELIGHT_LATENCY = new LongAdder(); // start of relight to completion, summed per chunk
	public static final LongAdder RELIGHTS = new LongAdder();
	public static final LongAdder SAVES = new LongAdder();
	public static final LongAdder TICKS = new LongAdder();
	private static volatile long startNanos;

	private LucTimings() {
	}

	public static void reset() {
		for (LongAdder adder : new LongAdder[] {SUBMIT, PROCESS, FLUSH, PREFLIGHT, SAVE, BROADCAST, VOXY, RELIGHT_LATENCY, RELIGHTS, SAVES, TICKS}) {
			adder.reset();
		}
		startNanos = System.nanoTime();
	}

	private static String ms(LongAdder adder) {
		return adder.sum() / 1_000_000 + "ms";
	}

	public static String summary() {
		long relights = Math.max(1, RELIGHTS.sum());
		return "wall=" + (System.nanoTime() - startNanos) / 1_000_000 + "ms ticks=" + TICKS.sum()
			+ " | server-thread: submit=" + ms(SUBMIT) + " process=" + ms(PROCESS) + " (save=" + ms(SAVE) + " x" + SAVES.sum()
			+ ", broadcast=" + ms(BROADCAST) + ", voxy=" + ms(VOXY) + ") flush=" + ms(FLUSH)
			+ " | async: preflight(cpu)=" + ms(PREFLIGHT)
			+ " | relight latency avg=" + RELIGHT_LATENCY.sum() / relights / 1_000_000 + "ms over " + RELIGHTS.sum() + " chunks";
	}
}
