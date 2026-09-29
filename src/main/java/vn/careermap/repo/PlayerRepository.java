package vn.careermap.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.careermap.domain.Player;

public interface PlayerRepository extends JpaRepository<Player, Long> {
  Optional<Player> findByPlayerKey(String playerKey);
}