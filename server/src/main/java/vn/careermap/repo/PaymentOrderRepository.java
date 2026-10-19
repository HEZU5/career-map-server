package vn.careermap.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.careermap.domain.PaymentOrder;
import vn.careermap.domain.PaymentStatus;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, String> {

  /** Tra đơn theo khoá ({@code vnp_TxnRef}) VÀ đúng người mua — chặn xem đơn
   *  của người khác. */
  Optional<PaymentOrder> findByIdAndPlayerKey(String id, String playerKey);

  /** Lịch sử đơn của một người, mới nhất trước. */
  List<PaymentOrder> findByPlayerKeyOrderByCreatedAtDesc(String playerKey);

  /** Đơn mới nhất của người ở một trạng thái (PENDING để tái dùng, PAID để
   *  xem lại). */
  Optional<PaymentOrder> findFirstByPlayerKeyAndStatusOrderByCreatedAtDesc(
      String playerKey, PaymentStatus status);
}
