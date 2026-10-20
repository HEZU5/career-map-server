package vn.careermap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chốt hồi quy: các method quét của đồng hồ server PHẢI có {@code @Transactional}.
 *
 * <p>Vì sao cần test này: {@code Room.players} là collection LAZY và
 * {@code spring.jpa.open-in-view=false}. Đọc nó ngoài transaction sẽ ném
 * {@code LazyInitializationException}. Với {@code skipAwayTurns()} thiếu
 * {@code @Transactional}, lỗi đó nổ ra đúng ở {@code GameTickScheduler} — nơi
 * try/catch nuốt mất — khiến {@code sweepExpiredTurns()} (gọi SAU đó) không
 * bao giờ chạy: cả đường server tự tung chết lặng.
 *
 * <p>Unit test không bắt được {@code LazyInitializationException} (cần DB thật),
 * nhưng test này bắt được đúng thứ cần bảo vệ: ai đó XOÁ annotation.
 *
 * <p>Thuần JUnit + AssertJ, không Spring context, không database.
 */
class TurnSchedulerTransactionalTest {

  @Test
  void skipAwayTurnsPhaiCoTransactional() {
    Transactional tx = annotationOf(TurnScheduler.class, "skipAwayTurns");
    assertThat(tx).isNotNull();
  }

  @Test
  void sweepExpiredTurnsPhaiCoTransactional() {
    Transactional tx = annotationOf(TurnScheduler.class, "sweepExpiredTurns");
    assertThat(tx).isNotNull();
  }

  @Test
  void processorPhaiTachTransactionRiengChoTungPhong() {
    Transactional sweep =
        annotationOf(TurnSweepProcessor.class, "sweepExpiredRoom", String.class, Instant.class);
    assertThat(sweep).isNotNull();
    assertThat(sweep.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

    Transactional away =
        annotationOf(TurnSweepProcessor.class, "skipAwayRoom", String.class, Instant.class);
    assertThat(away).isNotNull();
    assertThat(away.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }

  @Test
  void processorKhongDuocCoTransactionalMacDinh() {
    // REQUIRES_NEW là BẮT BUỘC: nếu là REQUIRED (mặc định) thì transaction
    // của phòng chỉ JOIN vào transaction của nhịp quét ⇒ xung đột optimistic-lock
    // của một phòng lại rollback cả nhịp quét (đúng bệnh I3).
    Transactional sweep =
        annotationOf(TurnSweepProcessor.class, "sweepExpiredRoom", String.class, Instant.class);
    assertThat(sweep.propagation()).isNotEqualTo(Propagation.REQUIRED);
  }

  private Transactional annotationOf(Class<?> type, String name, Class<?>... params) {
    Method method;
    try {
      method = type.getMethod(name, params);
    } catch (NoSuchMethodException e) {
      throw new AssertionError("không tìm thấy method " + type.getSimpleName() + "." + name, e);
    }
    return method.getAnnotation(Transactional.class);
  }
}
