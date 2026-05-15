package com.baozhu.mmrag.service.retrieval;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.util.ObjectBuilder;

import java.util.List;

/**
 * Shared multi-tenant permission clause used by every {@link RetrievalStrategy}.
 * A row is accessible if any of the following is true:
 *
 * <ul>
 *   <li>the row was uploaded by the requesting user (matches
 *       {@code userId}); OR</li>
 *   <li>the row is marked public ({@code isPublic = true}); OR</li>
 *   <li>the row's {@code orgTag} is one the requesting user's effective
 *       org-tag set (the user's direct memberships plus any tags inherited
 *       through the organisation hierarchy).</li>
 * </ul>
 *
 * <p>The clause is identical in shape to the one in the legacy
 * {@code HybridSearchService} so existing tenant semantics are preserved
 * across the refactored strategies. The only difference is that the field
 * name {@code isPublic} matches the Elasticsearch mapping (the legacy
 * Jackson-default {@code public} field name is fixed by the
 * {@code @JsonProperty("isPublic")} annotation on
 * {@code EsDocument#isPublic}).
 */
final class PermissionFilter {

    private PermissionFilter() {
    }

    /**
     * Adds the disjunctive permission clauses to {@code bf} and returns
     * the same builder. Designed to be called from a strategy as
     * <pre>
     * .filter(f -&gt; f.bool(bf -&gt; PermissionFilter.applyTo(bf, userDbId, orgTags)))
     * </pre>
     * so {@code bf}'s return type satisfies the
     * {@code Function<BoolQuery.Builder, ObjectBuilder<BoolQuery>>}
     * required by the ES client.
     *
     * @param bf          the boolean-clause builder being assembled
     * @param userDbId    the requesting user's database id (for the userId
     *                    clause)
     * @param userOrgTags the user's effective org-tag set; may be empty
     */
    static ObjectBuilder<BoolQuery> applyTo(
            BoolQuery.Builder bf, String userDbId, List<String> userOrgTags) {
        bf.should(s -> s.term(t -> t.field("userId").value(userDbId)));
        bf.should(s -> s.term(t -> t.field("isPublic").value(true)));
        if (userOrgTags != null && !userOrgTags.isEmpty()) {
            if (userOrgTags.size() == 1) {
                bf.should(s -> s.term(t -> t.field("orgTag").value(userOrgTags.get(0))));
            } else {
                bf.should(s -> s.bool(inner -> {
                    userOrgTags.forEach(tag ->
                            inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                    return inner;
                }));
            }
        }
        bf.minimumShouldMatch("1");
        return bf;
    }
}
