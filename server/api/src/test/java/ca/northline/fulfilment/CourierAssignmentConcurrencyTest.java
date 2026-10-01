package ca.northline.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.fulfilment.api.DeliveryRequests;
import ca.northline.fulfilment.application.DispatchUseCases.AssignCouriers;
import ca.northline.fulfilment.application.DispatchUseCases.PlanRuns;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import ca.northline.support.MovableClock;
import ca.northline.support.TestJwt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * S-86: courier assignment under concurrency. Several assigners (replicas' jobs, the console's "plan now") race over
 * more runs than couriers: every run gets at most one courier and every courier at most one run; two staff giving one
 * courier two runs at once — one wins, the other gets 409 {@code courier_busy}. Each test has its own market.
 */
@Import(MovableClock.Config.class)
class CourierAssignmentConcurrencyTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeliveryRequests requests;

    @Autowired
    PlanRuns plan;

    @Autowired
    AssignCouriers assign;

    @Autowired
    MovableClock clock;

    private String market() {
        return "Raceville " + Ids.next().substring(16);
    }

    /** A direct goods delivery whose shop has packed: planned as its own run at once. */
    private String directDelivery(String market) {
        var orderId = Ids.next();
        var shop = Ids.next();
        requests.request(new DeliveryRequests.Request(
                orderId,
                "NL-" + orderId.substring(20),
                "goods",
                "direct",
                market,
                null,
                List.of(shop),
                new DeliveryRequests.Dropoff("1 Test St", null, market, "T0T 0T0", null, null, null),
                null,
                null));
        requests.packed(orderId, shop, clock.instant());
        return orderId;
    }

    /** A courier of the market with a shift that is on, available. */
    private String courierOnShift(String market) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into fulfilment.couriers (id, user_id, market, vehicle, status, active)
                        values (?, ?, ?, 'bike', 'available', true)""")
                .params(id, data.user("Courier " + id.substring(20)), market)
                .update();
        jdbc.sql("""
                        insert into fulfilment.shifts (id, courier_id, starts_at, ends_at, state, started_at)
                        values (?, ?, now() - interval '1 hour', now() + interval '5 hours', 'on', now())""").params(Ids.next(), id).update();
        return id;
    }

    @Test
    void racingAssignersGiveEachRunOneCourierAndEachCourierOneRun() throws Exception {
        clock.reset();
        var market = market();
        for (int i = 0; i < 6; i++) {
            directDelivery(market);
        }
        var couriers = List.of(courierOnShift(market), courierOnShift(market), courierOnShift(market));
        assertThat(plan.plan(market)).isEqualTo(6);

        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++) {
                Callable<Integer> task = () -> {
                    start.await();
                    return assign.assign(market);
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int total = 0;
            for (var r : results) {
                total += r.get();
            }
            assertThat(total).isEqualTo(3);
        }
        var assigned = jdbc.sql("""
                        select courier_id from fulfilment.runs
                         where market = ? and courier_id is not null and state <> 'done'""").params(market).query(String.class).list();
        assertThat(assigned).hasSize(3).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(couriers);
        assertThat(jdbc.sql("select count(*) from fulfilment.runs where market = ? and courier_id is null")
                        .params(market)
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
        assertThat(jdbc.sql("select distinct status from fulfilment.couriers where market = ?")
                        .params(market)
                        .query(String.class)
                        .list())
                .containsExactly("on_run");
    }

    @Test
    void twoStaffGivingOneCourierTwoRunsAtOnce_oneWins() throws Exception {
        clock.reset();
        var market = market();
        directDelivery(market);
        directDelivery(market);
        plan.plan(market);
        var runs = jdbc.sql("select id from fulfilment.runs where market = ? order by id")
                .params(market)
                .query(String.class)
                .list();
        var courier = courierOnShift(market);
        var staff = data.user("Dee Dispatcher");
        var start = new CountDownLatch(1);
        List<Future<Integer>> codes = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var run : runs) {
                Callable<Integer> task = () -> {
                    start.await();
                    return mvc.perform(MockMvcRequestBuilders.post("/api/v1/console/fulfilment/runs/{id}/assign", run)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"courierId\":\"%s\"}".formatted(courier))
                                    .with(TestJwt.staff(staff)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };
                codes.add(pool.submit(task));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var c : codes) {
                statuses.add(c.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.sql("select count(*) from fulfilment.runs where courier_id = ?")
                        .params(courier)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
