package io.github.baoloc1993.zalobot.config;

import io.github.baoloc1993.zalobot.service.ZaloService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZaloSchedulerTest {

    @Test
    void tickIsSkippedWhileThePreviousRunIsStillInFlight() {
        ZaloService zaloService = mock(ZaloService.class);
        Sinks.Many<String> firstRun = Sinks.many().unicast().onBackpressureBuffer();
        when(zaloService.readMessages()).thenReturn(firstRun.asFlux(), Flux.empty());
        ZaloScheduler scheduler = new ZaloScheduler(zaloService);

        scheduler.readMessages();   // starts, never completes yet
        scheduler.readMessages();   // overlapping tick
        scheduler.readMessages();   // overlapping tick
        verify(zaloService, times(1)).readMessages();

        firstRun.tryEmitComplete(); // first run finishes
        scheduler.readMessages();   // next tick runs again
        verify(zaloService, times(2)).readMessages();
    }

    @Test
    void failedRunDoesNotBlockLaterTicks() {
        ZaloService zaloService = mock(ZaloService.class);
        when(zaloService.readMessages()).thenReturn(Flux.error(new IllegalStateException("boom")), Flux.empty());
        ZaloScheduler scheduler = new ZaloScheduler(zaloService);

        scheduler.readMessages();
        scheduler.readMessages();

        verify(zaloService, times(2)).readMessages();
    }
}
