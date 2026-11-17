package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.PromotionRequest;
import com.movie.ticket_booking.dto.PromotionResponse;
import com.movie.ticket_booking.dto.VoucherResponse;
import com.movie.ticket_booking.entity.Promotion;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.UserVoucher;
import com.movie.ticket_booking.entity.UserVoucherId;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.PromotionKind;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.PromotionRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.repository.UserVoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PromotionServiceTest {
    private PromotionRepository promotions;
    private UserVoucherRepository vouchers;
    private PromotionServiceImpl service;

    @BeforeEach
    void setUp() {
        promotions = mock(PromotionRepository.class);
        vouchers = mock(UserVoucherRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("a@b.vn")).thenReturn(Optional.of(User.builder().id(7L).email("a@b.vn").build()));
        service = new PromotionServiceImpl(promotions, vouchers, users);
        when(promotions.save(any(Promotion.class))).thenAnswer(i -> {
            Promotion p = i.getArgument(0);
            if (p.getId() == null) p.setId(3L);
            return p;
        });
    }

    private static PromotionRequest percent(String code, Integer percent) {
        return new PromotionRequest(code, "Title", null, null, null, null, PromotionKind.PERCENT_OFF, percent, 50_000L, null,
                List.of(DayOfWeek.TUESDAY, DayOfWeek.TUESDAY), List.of(MovieFormat.TWO_D), true, true, null, null, null, 2);
    }

    @Test
    void createUppercasesTheCodeStoresTheRulesAndDropsFieldsOfOtherKinds() {
        PromotionResponse created = service.create(percent("ceta20", 20));
        assertThat(created.code()).isEqualTo("CETA20");
        assertThat(created.weekdays()).containsExactly("TUESDAY");
        assertThat(created.formats()).containsExactly("TWO_D");
        assertThat(created.fixedPrice()).isNull();
        assertThat(created.active()).isTrue();
    }

    @Test
    void adminsSeeInactiveOffersToo() {
        when(promotions.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(
                Promotion.builder().id(1L).code("A").title("a").kind(PromotionKind.COUNTER_PERK).active(true).build(),
                Promotion.builder().id(2L).code("B").title("b").kind(PromotionKind.COUNTER_PERK).active(false).build()));
        assertThat(service.listAll()).extracting(PromotionResponse::active).containsExactly(true, false);
    }

    @Test
    void aPercentOfferNeedsAPercent() {
        assertThatThrownBy(() -> service.create(percent("X", null))).isInstanceOf(BusinessException.class);
        verify(promotions, never()).save(any());
    }

    @Test
    void anOfferCannotEndBeforeItStarts() {
        PromotionRequest r = new PromotionRequest("X", "T", null, null, null, null, PromotionKind.COUNTER_PERK, null, null, null,
                null, null, false, false, Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null, null);
        assertThatThrownBy(() -> service.create(r)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aDuplicateCodeIs409() {
        when(promotions.existsByCode("DUP")).thenReturn(true);
        assertThatThrownBy(() -> service.create(percent("dup", 10)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void savingToTheWalletIsIdempotent() {
        when(promotions.findById(3L)).thenReturn(Optional.of(Promotion.builder().id(3L).code("P").title("t").kind(PromotionKind.COUNTER_PERK).active(true).build()));
        UserVoucherId id = new UserVoucherId(7L, 3L);
        UserVoucher existing = UserVoucher.builder().id(id).savedAt(Instant.parse("2026-11-01T00:00:00Z")).build();
        when(vouchers.findById(id)).thenReturn(Optional.empty(), Optional.of(existing));
        when(vouchers.save(any(UserVoucher.class))).thenAnswer(i -> i.getArgument(0));

        service.save("a@b.vn", 3L);
        VoucherResponse again = service.save("a@b.vn", 3L);

        verify(vouchers, times(1)).save(any(UserVoucher.class));
        assertThat(again.savedAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void anInactiveOrUnknownOfferCannotBeSaved() {
        when(promotions.findById(3L)).thenReturn(Optional.of(Promotion.builder().id(3L).code("P").title("t").kind(PromotionKind.COUNTER_PERK).active(false).build()));
        assertThatThrownBy(() -> service.save("a@b.vn", 3L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> service.save("a@b.vn", 99L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void theWalletListsSavedOffersNewestFirst() {
        when(vouchers.findByIdUserIdOrderBySavedAtDesc(7L)).thenReturn(List.of(
                UserVoucher.builder().id(new UserVoucherId(7L, 2L)).savedAt(Instant.parse("2026-11-02T00:00:00Z")).build(),
                UserVoucher.builder().id(new UserVoucherId(7L, 1L)).savedAt(Instant.parse("2026-11-01T00:00:00Z")).build()));
        when(promotions.findById(2L)).thenReturn(Optional.of(Promotion.builder().id(2L).code("B").title("b").kind(PromotionKind.COUNTER_PERK).build()));
        when(promotions.findById(1L)).thenReturn(Optional.of(Promotion.builder().id(1L).code("A").title("a").kind(PromotionKind.COUNTER_PERK).build()));

        assertThat(service.wallet("a@b.vn")).extracting(v -> v.promotion().code()).containsExactly("B", "A");
    }

    @Test
    void removingFromTheWalletDeletesTheCallersOwnRow() {
        service.remove("a@b.vn", 3L);
        verify(vouchers).deleteById(new UserVoucherId(7L, 3L));
    }
}
