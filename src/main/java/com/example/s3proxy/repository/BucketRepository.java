package com.example.s3proxy.repository;

import com.example.s3proxy.entity.BucketEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BucketRepository extends JpaRepository<BucketEntity, Long> {
    Optional<BucketEntity> findByName(String name);

    boolean existsByName(String name);
}
