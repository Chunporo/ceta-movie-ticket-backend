package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.entity.enums.NotificationType;

/** Vietnamese default copy (D-25); the app may localise from {@code type}. Returns {title, body}. */
final class NotificationTexts {
    static final int BODY_MAX = 500;

    private NotificationTexts() {}

    static String[] of(NotificationType type, Long bookingId, Object... args) {
        return switch (type) {
            case ORDER_PAID -> new String[]{"Đặt vé thành công",
                    "Đơn #" + bookingId + " đã được thanh toán. Mở vé để lấy mã QR vào rạp."};
            case REFUND_PENDING -> new String[]{"Đang xử lý hoàn tiền",
                    "Đơn #" + bookingId + " sẽ được hoàn tiền trong vòng 24 giờ."};
            case REFUND_DONE -> new String[]{"Đã hoàn tiền",
                    "Đơn #" + bookingId + " đã được hoàn tiền. Vui lòng kiểm tra tài khoản của bạn."};
            case SHOWTIME_CANCELLED -> new String[]{"Suất chiếu bị huỷ", cut("Suất chiếu của đơn #" + bookingId
                    + " đã bị huỷ" + (args.length > 0 ? ": " + args[0] : "") + ". Bạn sẽ được hoàn tiền đầy đủ.")};
            case SHOWTIME_REMINDER -> new String[]{"Sắp đến giờ chiếu", reminderBody(bookingId)};
            case MOVIE_ON_SALE -> new String[]{"Phim đã mở bán vé", cut("\"" + (args.length > 0 ? args[0] : "Phim")
                    + "\" đã mở bán vé. Chọn suất chiếu và đặt vé ngay.")};
        };
    }

    static String reminderBody(Long bookingId) {
        return reminderPrefix() + bookingId + reminderSuffix();
    }

    static String reminderPrefix() {
        return "Suất chiếu của đơn #";
    }

    static String reminderSuffix() {
        return " sắp bắt đầu. Mở vé để vào rạp.";
    }

    private static String cut(String s) {
        return s.length() <= BODY_MAX ? s : s.substring(0, BODY_MAX - 1) + "…";
    }
}
