package dev.gateway.webhook.service.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.gateway.webhook.service.payment.application.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 중복 제거 판단만 본다. 게이트웨이 전달이 at-least-once 이므로 이 서비스에서 가장 중요한 로직이다.
 */
class WebhookReceiverTest {

    private PaymentService paymentService;
    private ProcessedEventRepository processedEvents;
    private WebhookReceiver receiver;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        processedEvents = mock(ProcessedEventRepository.class);
        receiver = new WebhookReceiver(paymentService, processedEvents);
    }

    @Test
    @DisplayName("처음 보는 이벤트면 결제로 넘긴다")
    void processesNewEvent() {
        when(processedEvents.existsById("evt-1")).thenReturn(false);

        receiver.receivePaymentDelivery("evt-1", new PaymentEvent(123L, 1_000));

        verify(paymentService).processPayment(123L);
    }

    @Test
    @DisplayName("이미 처리한 이벤트면 결제하지 않는다")
    void skipsDuplicateEvent() {
        when(processedEvents.existsById("evt-1")).thenReturn(true);

        receiver.receivePaymentDelivery("evt-1", new PaymentEvent(123L, 1_000));

        verify(paymentService, never()).processPayment(anyLong());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("중복이어도 예외를 던지지 않는다 — 게이트웨이에 200 을 줘야 재시도가 멈춘다")
    void duplicateDoesNotThrow() {
        when(processedEvents.existsById("evt-1")).thenReturn(true);

        assertThatCode(() -> receiver.receivePaymentDelivery("evt-1", new PaymentEvent(123L, 1_000)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("처리한 이벤트는 기록을 남긴다")
    void recordsProcessedEvent() {
        when(processedEvents.existsById("evt-9")).thenReturn(false);

        receiver.receivePaymentDelivery("evt-9", new PaymentEvent(456L, 2_000));

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(captor.capture());
        assertThat(captor.getValue().getGatewayEventId()).isEqualTo("evt-9");
        assertThat(captor.getValue().getOrderId()).isEqualTo(456L);
        assertThat(captor.getValue().getProcessedAt()).isNotNull();
    }
}
