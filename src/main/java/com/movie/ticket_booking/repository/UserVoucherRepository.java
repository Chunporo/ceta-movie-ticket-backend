package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.UserVoucher;
import com.movie.ticket_booking.entity.UserVoucherId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserVoucherRepository extends JpaRepository<UserVoucher, UserVoucherId> {
    List<UserVoucher> findByIdUserIdOrderBySavedAtDesc(Long userId);

    long countByIdUserId(Long userId);
}
