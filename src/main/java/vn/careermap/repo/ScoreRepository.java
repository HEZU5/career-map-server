package vn.careermap.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.careermap.domain.Score;

public interface ScoreRepository extends JpaRepository<Score, Long> {
  List<Score> findByPlayerId(Long playerId);
}