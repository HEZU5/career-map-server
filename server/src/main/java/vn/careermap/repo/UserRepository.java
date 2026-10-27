package vn.careermap.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.careermap.domain.User;

public interface UserRepository extends JpaRepository<User, String> {}
