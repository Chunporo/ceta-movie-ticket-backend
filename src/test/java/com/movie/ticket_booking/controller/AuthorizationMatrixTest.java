package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.service.*;
import com.movie.ticket_booking.service.payment.ApplyPaymentResultUseCase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * T9: who may call what. Anonymous -> 401 unless public; a plain user -> 403 on admin endpoints;
 * an admin is never turned away. Handler outcomes (200/400/500 from mocked services) do not matter here.
 */
@WebMvcTest(controllers = {AuthController.class, BookingController.class, BookingV1Controller.class,
        AdminRefundController.class, GenreController.class, MovieController.class, RoomController.class,
        SeatController.class, ShowtimeController.class, PaymentV1Controller.class, TicketV1Controller.class, AdminTicketController.class,
        AdminShowtimeController.class, NotificationController.class})
@Import(SecurityConfig.class)
class AuthorizationMatrixTest {
    enum Access { PUBLIC, AUTHENTICATED, ADMIN }

    record Endpoint(HttpMethod method, String path, Access access) {
        @Override public String toString() { return access + " " + method + " " + path; }
    }

    @Autowired private MockMvc mockMvc;
    @MockBean private AuthService authService;
    @MockBean private MovieService movieService;
    @MockBean private GenreService genreService;
    @MockBean private RoomService roomService;
    @MockBean private SeatService seatService;
    @MockBean private ShowtimeService showtimeService;
    @MockBean private com.movie.ticket_booking.service.notification.NotificationService notificationService;
    @MockBean private ShowtimeCancellationService showtimeCancellationService;
    @MockBean private BookingService bookingService;
    @MockBean private BookingCreationService bookingCreationService;
    @MockBean private BookingLifecycleService bookingLifecycleService;
    @MockBean private PaidBookingCancellationService paidBookingCancellationService;
    @MockBean private RefundService refundService;
    @MockBean private ApplyPaymentResultUseCase applyPaymentResultUseCase;
    @MockBean private com.movie.ticket_booking.service.payment.PaymentUrlService paymentUrlService;
    @MockBean private com.movie.ticket_booking.service.email.EmailService emailService;
    @MockBean private com.movie.ticket_booking.service.ticket.TicketQueryService ticketQueryService;
    @MockBean private com.movie.ticket_booking.service.ticket.CheckInService checkInService;

    static List<Endpoint> endpoints() {
        String uuid = UUID.randomUUID().toString();
        return List.of(
                new Endpoint(HttpMethod.POST, "/api/auth/register", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/auth/login", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/auth/firebase", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/auth/refresh", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/auth/logout", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/auth/me", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.POST, "/api/auth/forgot-password", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/auth/reset-password", Access.PUBLIC),
                new Endpoint(HttpMethod.PATCH, "/api/auth/me", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.PUT, "/api/auth/me/password", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.DELETE, "/api/auth/me", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.POST, "/api/v1/auth/login", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/v1/auth/me", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/movies", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/v1/movies", Access.ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/showtimes", Access.PUBLIC),
                new Endpoint(HttpMethod.DELETE, "/api/v1/showtimes/1", Access.ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/genres", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/v1/bookings", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/notifications", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/notifications/unread-count", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.PUT, "/api/v1/notifications/1/read", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.PUT, "/api/v1/notifications/read-all", Access.AUTHENTICATED),

                new Endpoint(HttpMethod.GET, "/api/genres", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/genres", Access.ADMIN),
                new Endpoint(HttpMethod.DELETE, "/api/genres/1", Access.ADMIN),
                new Endpoint(HttpMethod.GET, "/api/movies", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/movies/" + uuid, Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/movies", Access.ADMIN),
                new Endpoint(HttpMethod.PUT, "/api/movies/" + uuid, Access.ADMIN),
                new Endpoint(HttpMethod.DELETE, "/api/movies/" + uuid, Access.ADMIN),
                new Endpoint(HttpMethod.GET, "/api/rooms", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/rooms/1", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/rooms/1/seats", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/rooms", Access.ADMIN),
                new Endpoint(HttpMethod.PUT, "/api/rooms/1", Access.ADMIN),
                new Endpoint(HttpMethod.DELETE, "/api/rooms/1", Access.ADMIN),
                new Endpoint(HttpMethod.PATCH, "/api/seats/1", Access.ADMIN),
                new Endpoint(HttpMethod.GET, "/api/showtimes", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/showtimes/1", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/showtimes/1/seats", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/showtimes", Access.ADMIN),
                new Endpoint(HttpMethod.PUT, "/api/showtimes/1", Access.ADMIN),
                new Endpoint(HttpMethod.DELETE, "/api/showtimes/1", Access.ADMIN),

                new Endpoint(HttpMethod.GET, "/api/bookings/my-bookings", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/tickets/my", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/tickets/1", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.POST, "/api/v1/bookings", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/bookings/1", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.DELETE, "/api/v1/bookings/1", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.POST, "/api/v1/bookings/1/cancel", Access.AUTHENTICATED),

                new Endpoint(HttpMethod.POST, "/api/v1/payments/vnpay/create-url", Access.AUTHENTICATED),
                new Endpoint(HttpMethod.GET, "/api/v1/payments/vnpay/ipn", Access.PUBLIC),
                new Endpoint(HttpMethod.POST, "/api/v1/payments/vnpay/ipn", Access.PUBLIC),
                new Endpoint(HttpMethod.GET, "/api/v1/payments/vnpay/return", Access.PUBLIC),

                new Endpoint(HttpMethod.POST, "/api/v1/admin/tickets/check-in", Access.ADMIN),
                new Endpoint(HttpMethod.POST, "/api/v1/admin/showtimes/1/cancel", Access.ADMIN),
                new Endpoint(HttpMethod.GET, "/api/v1/admin/refunds", Access.ADMIN),
                new Endpoint(HttpMethod.PUT, "/api/v1/admin/refunds/1/done", Access.ADMIN),
                new Endpoint(HttpMethod.PUT, "/api/v1/admin/refunds/1/failed", Access.ADMIN));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void anonymousUserAndAdminAreTreatedAsTheEndpointDemands(Endpoint e) throws Exception {
        int anonymous = status(e, null);
        int user = status(e, jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")));
        int admin = status(e, jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));

        if (e.access() == Access.PUBLIC) {
            assertNotEquals(401, anonymous, "anonymous must reach a public endpoint");
            assertNotEquals(403, anonymous, "anonymous must reach a public endpoint");
        } else {
            assertEquals(401, anonymous, "anonymous must be asked to log in");
        }
        if (e.access() == Access.ADMIN) {
            assertEquals(403, user, "an ordinary user must be refused");
        } else {
            assertNotEquals(401, user);
            assertNotEquals(403, user);
        }
        assertNotEquals(401, admin);
        assertNotEquals(403, admin);
    }

    private int status(Endpoint e, RequestPostProcessor identity) throws Exception {
        var req = request(e.method(), e.path()).contentType(MediaType.APPLICATION_JSON).content("{}");
        if (identity != null) {
            req = req.with(identity);
        }
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    /** The pre-hold checkout and the unchecked VNPay callback are gone: nothing may answer on those paths. */
    @org.junit.jupiter.api.Test
    void retiredLegacyPaymentPathsAreGone() throws Exception {
        var user = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
        for (String[] gone : new String[][]{{"POST", "/api/bookings/checkout"}, {"GET", "/api/payment/vnpay/callback"}}) {
            var req = request(HttpMethod.valueOf(gone[0]), gone[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
            assertEquals(401, mockMvc.perform(req).andReturn().getResponse().getStatus(), gone[1] + " anonymous");
            assertEquals(404, mockMvc.perform(req.with(user)).andReturn().getResponse().getStatus(), gone[1] + " logged in");
        }
    }
}
