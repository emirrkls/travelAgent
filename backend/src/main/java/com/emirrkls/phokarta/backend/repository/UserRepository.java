package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("""
            select u from User u
            where lower(u.email) = lower(:identifier)
               or lower(u.username) = lower(:identifier)
            """)
    Optional<User> findByEmailOrUsernameIgnoreCase(@Param("identifier") String identifier);

    @Query("""
            select case when count(u) > 0 then true else false end from User u
            where lower(u.email) = lower(:email)
            """)
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("""
            select case when count(u) > 0 then true else false end from User u
            where lower(u.username) = lower(:username)
            """)
    boolean existsByUsernameIgnoreCase(@Param("username") String username);
}
