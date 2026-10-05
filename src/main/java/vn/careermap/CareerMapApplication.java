package vn.careermap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling // RoomCleanupService: dọn phòng bỏ hoang.
@SpringBootApplication
public class CareerMapApplication {
  public static void main(String[] args) { SpringApplication.run(CareerMapApplication.class, args); }
}
