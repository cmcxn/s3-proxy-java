package com.example.s3proxy.service;

import com.example.s3proxy.entity.BucketEntity;
import com.example.s3proxy.repository.BucketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BucketService {

    private static final Logger log = LoggerFactory.getLogger(BucketService.class);

    private final BucketRepository bucketRepository;

    public BucketService(BucketRepository bucketRepository) {
        this.bucketRepository = bucketRepository;
    }

    public boolean bucketExists(String bucket) {
        String normalized = normalizeBucket(bucket);
        boolean exists = bucketRepository.existsByName(normalized);
        log.debug("Bucket exists check: bucket={}, exists={}", normalized, exists);
        return exists;
    }

    public void ensureBucketExists(String bucket) {
        String normalized = normalizeBucket(bucket);
        if (bucketRepository.existsByName(normalized)) {
            return;
        }

        try {
            bucketRepository.save(new BucketEntity(normalized));
            log.info("Created logical bucket: {}", normalized);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Bucket {} was created concurrently", normalized, ex);
        }
    }

    public List<BucketEntity> listBuckets() {
        return bucketRepository.findAll();
    }

    private String normalizeBucket(String bucket) {
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket name cannot be null");
        }
        return bucket.trim();
    }
}
