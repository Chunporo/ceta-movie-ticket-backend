package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.PromotionRequest;
import com.movie.ticket_booking.dto.PromotionResponse;
import com.movie.ticket_booking.dto.VoucherResponse;
import com.movie.ticket_booking.entity.Promotion;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.UserVoucher;
import com.movie.ticket_booking.entity.UserVoucherId;
import com.movie.ticket_booking.entity.enums.PromotionKind;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.PromotionRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.repository.UserVoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PromotionServiceImpl implements PromotionService {
    private final PromotionRepository promotions;
    private final UserVoucherRepository vouchers;
    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public List<PromotionResponse> listOffers() {
        return promotions.findListed(Instant.now()).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromotionResponse> listAll() {
        return promotions.findAll(org.springframework.data.domain.Sort.by("sortOrder", "id")).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public PromotionResponse create(PromotionRequest request) {
        String code = request.code().toUpperCase(Locale.ROOT);
        if (promotions.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Promotion code already exists");
        }
        return toResponse(promotions.save(apply(new Promotion(), request)));
    }

    @Override
    @Transactional
    public PromotionResponse update(Long id, PromotionRequest request) {
        Promotion promotion = promotions.findById(id).orElseThrow(this::notFound);
        promotions.findByCode(request.code().toUpperCase(Locale.ROOT)).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Promotion code already exists");
        });
        // Bookings already made keep the discount they were given (booking_promotion).
        return toResponse(promotions.save(apply(promotion, request)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoucherResponse> wallet(String requesterEmail) {
        User user = findUser(requesterEmail);
        return vouchers.findByIdUserIdOrderBySavedAtDesc(user.getId()).stream()
                .map(v -> promotions.findById(v.getId().getPromotionId()).map(p -> new VoucherResponse(toResponse(p), v.getSavedAt())).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional
    public VoucherResponse save(String requesterEmail, Long promotionId) {
        User user = findUser(requesterEmail);
        Promotion promotion = promotions.findById(promotionId).filter(Promotion::isActive).orElseThrow(this::notFound);
        UserVoucherId id = new UserVoucherId(user.getId(), promotionId);
        UserVoucher saved = vouchers.findById(id).orElseGet(() -> vouchers.save(UserVoucher.builder().id(id).savedAt(Instant.now()).build()));
        return new VoucherResponse(toResponse(promotion), saved.getSavedAt());
    }

    @Override
    @Transactional
    public void remove(String requesterEmail, Long promotionId) {
        User user = findUser(requesterEmail);
        vouchers.deleteById(new UserVoucherId(user.getId(), promotionId));
    }

    private Promotion apply(Promotion p, PromotionRequest r) {
        validate(r);
        p.setCode(r.code().toUpperCase(Locale.ROOT));
        p.setTitle(r.title());
        p.setTitleEn(r.titleEn());
        p.setDescription(r.description());
        p.setDescriptionEn(r.descriptionEn());
        p.setImageUrl(r.imageUrl());
        p.setKind(r.kind());
        p.setPercentOff(r.kind() == PromotionKind.PERCENT_OFF ? r.percentOff() : null);
        p.setMaxDiscount(r.kind() == PromotionKind.PERCENT_OFF ? r.maxDiscount() : null);
        p.setFixedPrice(r.kind() == PromotionKind.FIXED_TICKET_PRICE ? r.fixedPrice() : null);
        p.setWeekdays(r.weekdays() == null || r.weekdays().isEmpty() ? null
                : r.weekdays().stream().map(Enum::name).distinct().collect(Collectors.joining(",")));
        p.setFormats(r.formats() == null || r.formats().isEmpty() ? null
                : r.formats().stream().map(Enum::name).distinct().collect(Collectors.joining(",")));
        p.setFirstOrderOnly(r.firstOrderOnly());
        p.setOncePerUser(r.oncePerUser());
        p.setValidFrom(r.validFrom());
        p.setValidUntil(r.validUntil());
        if (r.active() != null) p.setActive(r.active());
        if (r.sortOrder() != null) p.setSortOrder(r.sortOrder());
        return p;
    }

    private void validate(PromotionRequest r) {
        if (r.kind() == PromotionKind.PERCENT_OFF && r.percentOff() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "percentOff is required for PERCENT_OFF");
        }
        if (r.kind() == PromotionKind.FIXED_TICKET_PRICE && r.fixedPrice() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "fixedPrice is required for FIXED_TICKET_PRICE");
        }
        if (r.validFrom() != null && r.validUntil() != null && !r.validUntil().isAfter(r.validFrom())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "validUntil must be after validFrom");
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Promotion not found");
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private PromotionResponse toResponse(Promotion p) {
        return new PromotionResponse(p.getId(), p.getCode(), p.getTitle(), p.getTitleEn(), p.getDescription(), p.getDescriptionEn(),
                p.getImageUrl(), p.getKind(), p.getPercentOff(), p.getMaxDiscount(), p.getFixedPrice(),
                split(p.getWeekdays()), split(p.getFormats()), p.isFirstOrderOnly(), p.isOncePerUser(),
                p.getValidFrom(), p.getValidUntil(), p.isActive(), p.getSortOrder());
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split("\\s*,\\s*"));
    }
}
