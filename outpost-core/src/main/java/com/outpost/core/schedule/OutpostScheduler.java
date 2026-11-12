package com.outpost.core.schedule;

/**
 * 调度器 SPI，对应 {@code outpost.schedule.type}。
 */
public interface OutpostScheduler {

    String getType();

    void start();

    void stop();
}
