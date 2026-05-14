package com.yizhaoqi.smartpai.service.retrieval;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;

import java.util.List;
import java.util.function.Function;

/**
 * Shared multi-tenant permission clause used by every {@link RetrievalStrategy}.
 * A row is accessible if any of the following is true:
 *
 * <ul>
 *   <li>the row was uploaded by the requesting user, OR</li>
 *   <li>the row is marked public, OR</li>
 *   <li>the row's organisation tag is one the requesting user belongs to
 *       and the row is not private to a different user.</li>
 * </ul>
 *
 * <p>The clause is identical to the one in the legacy
 * {@code HybridSearchService} so existing tenant semantics are preserved
 * across the refactored strategies.
 */
final class PermissionFilter {

    private PermissionFilter() {
    }

    /**
     * Returns a BoolQuery builder consumer producing the disjunction
     * (user-owned OR public OR org-scoped). Caller wraps it in a
     * {@code .filter(f -> f.bool(PermissionFilter.apply(...)))} clause.
     */
    static Function<BoolQuery.Builder, BoolQuery.Builder> apply(
            String userDbId, List<String> userOrgTags) {
        return bf -> {
            bf.should(s -> s.term(t -> t.field("userId").value(userDbId)));
            bf.should(s -> s.term(t -> t.field("isPublic").value(true)));
            if (userOrgTags != null && !userOrgTags.isEmpty()) {
                bf.should(s -> s.bool(orgClause -> orgClause
                        .must(m -> m.terms(tt -> tt
                                .field("orgTag")
                                .terms(tv -> tv.value(
                                        userOrgTags.stream()
                                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                                .toList()))))
                        .mustNot(mn -> mn.term(t -> t.field("isPublic").value(false)
                                .boost(0.0f)))));
            }
            bf.minimumShouldMatch("1");
            return bf;
        };
    }
}
