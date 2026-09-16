package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.domain.entity.Collection;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ViewerAccessPolicyPrivacyTest {
    @Mock private BlockService blocks;
    @Mock private UserFollowRepository follows;
    @Mock private Visit visit;
    @Mock private Collection collection;
    @Mock private User author;
    private ViewerAccessPolicy policy;
    private UUID authorId;
    private UUID viewerId;

    @BeforeEach
    void setUp() {
        policy = new ViewerAccessPolicy(blocks, follows);
        authorId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        when(author.getId()).thenReturn(authorId);
    }

    @Test
    void ownerAlwaysReadsOwnPrivateExperience() {
        when(visit.getUser()).thenReturn(author);
        assertThat(policy.canViewVisit(visit, authorId)).isTrue();
    }

    @Test
    void anonymousReadsPublicExperienceFromPublicProfile() {
        visit(Visibility.PUBLIC, ProfileVisibility.PUBLIC);
        assertThat(policy.canViewVisit(visit, null)).isTrue();
    }

    @Test
    void anonymousCannotReadPublicExperienceFromPrivateProfile() {
        visit(Visibility.PUBLIC, ProfileVisibility.PRIVATE);
        assertThat(policy.canViewVisit(visit, null)).isFalse();
    }

    @Test
    void approvedFollowerReadsPublicExperienceFromPrivateProfile() {
        visit(Visibility.PUBLIC, ProfileVisibility.PRIVATE);
        when(follows.existsFollow(viewerId, authorId)).thenReturn(true);
        assertThat(policy.canViewVisit(visit, viewerId)).isTrue();
    }

    @Test
    void approvedFollowerAloneCannotReadFriendsExperience() {
        visit(Visibility.FRIENDS, ProfileVisibility.PRIVATE);
        when(follows.existsFollow(viewerId, authorId)).thenReturn(true);
        assertThat(policy.canViewVisit(visit, viewerId)).isFalse();
    }

    @Test
    void mutualApprovedFriendReadsFriendsExperience() {
        visit(Visibility.FRIENDS, ProfileVisibility.PRIVATE);
        when(follows.existsFollow(viewerId, authorId)).thenReturn(true);
        when(follows.areFriends(viewerId, authorId)).thenReturn(true);
        assertThat(policy.canViewVisit(visit, viewerId)).isTrue();
    }

    @Test
    void privateExperienceRemainsOwnerOnly() {
        visit(Visibility.PRIVATE, ProfileVisibility.PUBLIC);
        assertThat(policy.canViewVisit(visit, viewerId)).isFalse();
    }

    @Test
    void symmetricBlockOverridesOtherwisePublicExperience() {
        visit(Visibility.PUBLIC, ProfileVisibility.PUBLIC);
        when(blocks.isBlockedEitherDirection(viewerId, authorId)).thenReturn(true);
        assertThat(policy.canViewVisit(visit, viewerId)).isFalse();
    }

    @Test
    void privateProfileAlsoBoundsPublicCollection() {
        when(collection.getUser()).thenReturn(author);
        org.mockito.Mockito.lenient().when(collection.getVisibility()).thenReturn(Visibility.PUBLIC);
        when(author.getProfileVisibility()).thenReturn(ProfileVisibility.PRIVATE);
        assertThat(policy.canViewCollection(collection, null)).isFalse();
    }

    private void visit(Visibility visibility, ProfileVisibility profileVisibility) {
        org.mockito.Mockito.lenient().when(visit.getUser()).thenReturn(author);
        org.mockito.Mockito.lenient().when(visit.getVisibility()).thenReturn(visibility);
        org.mockito.Mockito.lenient().when(author.getProfileVisibility()).thenReturn(profileVisibility);
    }
}
