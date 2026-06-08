package com.rca.ingestion.repository;

import com.rca.common.model.RcaJob;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RcaJobRepository extends MongoRepository<RcaJob, String> {
}