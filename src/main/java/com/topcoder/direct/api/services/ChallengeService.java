/*
 * Copyright (C) 2014 - 2015 TopCoder Inc., All Rights Reserved.
 */
package com.topcoder.direct.api.services;

import com.appirio.tech.core.api.v2.CMCID;
import com.appirio.tech.core.api.v2.metadata.CountableMetadata;
import com.appirio.tech.core.api.v2.metadata.Metadata;
import com.appirio.tech.core.api.v2.model.annotation.ApiMapping;
import com.appirio.tech.core.api.v2.request.FieldSelector;
import com.appirio.tech.core.api.v2.request.FilterParameter;
import com.appirio.tech.core.api.v2.request.LimitQuery;
import com.appirio.tech.core.api.v2.request.OrderByQuery;
import com.appirio.tech.core.api.v2.request.QueryParameter;
import com.appirio.tech.core.api.v2.request.SortOrder;
import com.appirio.tech.core.api.v2.service.AbstractMetadataService;
import com.appirio.tech.core.api.v2.service.RESTQueryService;
import com.topcoder.direct.api.model.Challenge;
import com.topcoder.direct.api.model.MemberPrize;
import com.topcoder.direct.api.model.Prize;
import com.topcoder.direct.api.security.AccessLevel;
import com.topcoder.direct.api.security.DirectAuthenticationToken;
import com.topcoder.direct.api.security.SecurityUtil;
import com.topcoder.direct.dao.CatalogDAO;
import com.topcoder.direct.dao.ChallengeDAO;
import com.topcoder.direct.dao.UserDAO;
import com.topcoder.direct.exception.BadRequestException;
import com.topcoder.direct.exception.ServerInternalException;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.topcoder.direct.util.Helper.isNull;
import static com.topcoder.direct.util.ServiceHelper.parseFilterValueToValueList;
import static com.topcoder.direct.util.ServiceHelper.populateLimitQuery;

/**
 * This is the service implementation for the My Challenges API.
 * <p/>
 * <p>
 * Version 1.1 (TopCoder Direct API - Add New Date Filters for Challenges API)
 * - Add (startDateFrom, startDateTo), (endDateFrom, endDateTo) filters.
 * </p>
 * <p/>
 * <p>
 * Version 1.2 (TopCoder Direct API - Project Retrieval API)
 * - Refactor the common methods used by all service implementation out into {@link #com.topcoder.direct.util.ServiceHelper}
 * </p>
 *
 * @author j3_guile, Veve, GreatKevin
 * @version 1.2 (TopCoder Direct API - Project Retrieval API)
 * @since 1.0 (Topcoder Direct API - My Challenges API v1.0)
 */
@Service
public class ChallengeService extends AbstractMetadataService implements RESTQueryService<Challenge> {

    /**
     * Logger instance.
     */
    private static final Logger LOG = Logger.getLogger(ChallengeService.class);


    /**
     * Start/End Date formatter
     *
     * @since 1.1
     */
    private static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    /**
     * The max date to use when an upper bound is not specified in the date filter.
     *
     * @since 1.1
     */
    private static Date MAX_DATE = new Date(Long.MAX_VALUE);

    /**
     * The min date to use when a lower bound is not specified in the date filter.
     *
     * @since 1.1
     */
    private static Date MIN_DATE = new Date(0);

    /**
     * A placeholder to ensure there are no matches returned when search of lookup values
     * return nothing.
     */
    private static final List<Integer> NO_MATCHED_LOOKUP = Arrays.asList(-1);

    /**
     * The default sort field.
     */
    private static final String DEFAULT_SORT_FIELD = "challengeEndDate";

    /**
     * Filter for creator.
     */
    private static final String CREATOR_FILTER = " AND p.create_user = :creator_id\n";

    /**
     * Allowed type values for my created challenges api.
     */
    private static final List<String> ALLOWED_TYPE = Arrays.asList("active", "past", "draft");

    /**
     * The challenge type filter.
     */
    private static final String CHALLENGE_TYPE_FILTER = " AND p.project_category_id IN (:challenge_type_ids)\n";

    /**
     * The technology filter.
     */
    private static final String TECHNOLOGY_FILTER = " AND EXISTS (SELECT DISTINCT 1 FROM comp_technology ct "
            + "WHERE ct.comp_vers_id = pi1.value AND ct.technology_type_id IN (:technology_ids))\n";

    /**
     * The platform filter.
     */
    private static final String PLATFORM_FILTER = " AND EXISTS (SELECT 1 FROM project_platform pp "
            + "WHERE pp.project_platform_id IN (:platform_ids) AND p.project_id = pp.project_id)\n";

    /**
     * The direct project id filter.
     */
    private static final String DIRECT_PROJECT_ID_FILTER = " AND p.tc_direct_project_id IN (:direct_project_ids)\n";

    /**
     * The client id filter.
     */
    private static final String CLIENT_ID_FILTER = " AND client_billing_info.client_id IN (:client_ids)\n";

    /**
     * The billing id filter.
     */
    private static final String BILLING_ID_FILTER = " AND client_billing_info.billing_id IN (:billing_ids)\n";

    /**
     * The project id filter.
     */
    private static final String PROJECT_ID_FILTER = " AND p.project_id IN (:project_ids)\n";

    /**
     * The type filter.
     */
    private static final String TYPE_FILTER = " AND p.project_status_id IN (:type_id)\n";

    /**
     * The challenge start date filter.
     *
     * @since 1.1
     */
    private static final String START_DATE_FILTER = "AND (NVL(reg_phase.actual_start_time, reg_phase.scheduled_start_time) BETWEEN :startDateFrom AND :startDateTo)\n";

    /**
     * The challenge end date filter.
     *
     * @since 1.1
     */
    private static final String END_DATE_FILTER = "AND ((SELECT NVL(MAX(actual_end_time), MAX(scheduled_end_time)) FROM project_phase pp where pp.project_id = p.project_id) BETWEEN :endDateFrom AND :endDateTo)\n";

    /**
     * The active project status name.
     */
    private static final String ACTIVE_PROJECT_STATUS = "active";

    /**
     * The draft project status name.
     */
    private static final String DRAFT_PROJECT_STATUS = "draft";

    /**
     * The challenge prize type id.
     */
    private static final Integer CHALLENGE_PRIZE_TYPE = 15;

    /**
     * The checkpoint prize type id.
     */
    private static final Integer CHECKPOINT_PRIZE_TYPE = 14;

    /**
     * Field mapping for ordering.
     */
    private static final Map<String, String> ORDER_BY_FIELDS = new HashMap<String, String>() {
        {
            put("id", "challenge_id");
            put("challengename", "challenge_name");
            put("challengetype", "challenge_type");
            put("clientname", "client_name");
            put("clientid", "client_id");
            put("billingname", "billing_name");
            put("billingid", "billing_id");
            put("directprojectname", "direct_project_name");
            put("directprojectid", "direct_project_id");
            put("challengestartdate", "challenge_start_date");
            put("challengeenddate", "challenge_end_date");
            put("drpoints", "dr_points");
            put("challengestatus", "challenge_status");
            put("challengecreator", "challenge_creator");
        }
    };

    /**
     * The challenge DAO.
     */
    @Autowired
    private ChallengeDAO challengeDAO;

    /**
     * The catalog DAO.
     */
    @Autowired
    private CatalogDAO catalogDAO;

    /**
     * The user dao that used to retrieve data from database.
     */
    @Autowired
    private UserDAO userDAO;

    /**
     * Creates a new instance. Initializes the field map.
     */
    public ChallengeService() {
    }

    /**
     * Returns the resource path for challenges.
     *
     * @return {@value Challenge.#RESOURCE_PATH}
     */
    @ApiMapping(visible = false)
    public String getResourcePath() {
        return Challenge.RESOURCE_PATH;
    }

    /**
     * Not implemented.
     * <p/>
     * http://apps.topcoder.com/forums/?module=Thread&threadID=828408&start=0
     *
     * @param selector not used
     * @param recordId not used
     * @return none
     * @throws UnsupportedOperationException always
     */
    public Challenge handleGet(FieldSelector selector, CMCID recordId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Retrieves challenges using the provided specification.
     *
     * @param request the servlet request
     * @param query   the filter and output specification
     * @return the challenge data requested, may be empty, never null
     * @throws BadRequestException     for any validation failure in the request data
     * @throws UnauthorizedException   if the request principal is not logged in as a member or admin roles
     * @throws ServerInternalException for IOExceptions caught from the DAO
     *                                 Note: all RuntimeExceptions are propagated to the framework exception handlers.
     */
    public List<Challenge> handleGet(HttpServletRequest request, QueryParameter query) {
        DirectAuthenticationToken identity = SecurityUtil.getAuthentication(request);
        identity.authorize(AccessLevel.ADMIN, AccessLevel.MEMBER);

        List<Challenge> challenges = new ArrayList<Challenge>();
        try {
            Map<String, Object> sqlParameters = new HashMap<String, Object>();
            List<String> customFilter = new ArrayList<String>();

            // validate filters
            validateQuery(identity.getUserId(), query);

            // for access checking
            sqlParameters.put("user_id", identity.getUserId());

            if (query.getFilter().contains("creator")) {
                // Perform "My created challenges" flow
                customFilter = getCreatorChallengeFilters(identity, query, sqlParameters);
                sqlParameters.put("creator_id", identity.getUserId());
            } else {
                customFilter = getMyChallengeFilters(query, sqlParameters);
            }

            // limit results
            populateLimitQuery(query, sqlParameters);
            String orderClause = getOrderClause(query.getOrderByQuery());
            challenges = challengeDAO.getMyChallenge(customFilter, sqlParameters, orderClause);

            if (challenges.size() > 0) {
                mergePrizesToChallenges(challenges);
            }
        } catch (IOException e) {
            throw new ServerInternalException("An error occurred while querying for challenges", e);
        }

        return challenges;
    }

    /**
     * Generates the order by clause for challenges.
     *
     * @param query the order query requested
     * @return the order by clause
     */
    private String getOrderClause(OrderByQuery query) {
        StringBuilder order = new StringBuilder();
        String fldName = query.getOrderByField();
        if (fldName == null || fldName.trim().length() == 0) {
            fldName = DEFAULT_SORT_FIELD;
        }

        fldName = fldName.toLowerCase();
        if (ORDER_BY_FIELDS.get(fldName) == null) {
            throw new BadRequestException("Sorting is not supported for requested field.");
        }

        order.append(" ORDER BY " + ORDER_BY_FIELDS.get(fldName)).append(" ");
        if (query.getSortOrder() != null) { // specified direction
            if (query.getSortOrder() == SortOrder.ASC_NULLS_FIRST) {
                order.append("ASC ");
            } else if (query.getSortOrder() == SortOrder.DESC_NULLS_LAST) {
                order.append("DESC ");
            } else {
                throw new BadRequestException("Specified sort order is not supported. " + query.getSortOrder());
            }
        }

        // add a secondary sorting for non unique sort requests
        if (!"id".equalsIgnoreCase(fldName)) {
            order.append(", challenge_id DESC");
        }

        order.append("\n");
        return order.toString();
    }

    /**
     * Merges the corresponding Prizes to Challenges.
     *
     * @param challenges The challenges to be filled with Prizes.
     * @throws IOException When an error occurs while querying the prizes.
     */
    private void mergePrizesToChallenges(List<Challenge> challenges) throws IOException {

        // NOTE: get all the projects from the challenges retrieved to query the prizes efficiently
        List<Integer> projectIds = new ArrayList<Integer>();
        for (Challenge challenge : challenges) {
            projectIds.add(Integer.valueOf(challenge.getId().toString()));
        }
        Map<String, Object> sqlParameters = new HashMap<String, Object>();
        sqlParameters.put("project_ids", projectIds);

        List<Prize> prizes = challengeDAO.getMyChallengesPrizes(new ArrayList<String>(), sqlParameters);

        Map<Integer, List<Prize>> challengeId2PrizeMap = new HashMap<Integer, List<Prize>>();

        for (Prize prize : prizes) {
            Integer challengeId = prize.getChallengeId();
            if (isNull(challengeId2PrizeMap.get(challengeId))) {
                // We don't have prizes for this challenge
                List<Prize> list = new ArrayList<Prize>();
                list.add(prize);
                challengeId2PrizeMap.put(challengeId, list);
            } else {
                // Otherwise
                challengeId2PrizeMap.get(challengeId).add(prize);
            }
        }

        for (Challenge challenge : challenges) {
            List<Prize> challengePrizes = new ArrayList<Prize>();
            List<Prize> checkPointPrizes = new ArrayList<Prize>();
            Double totalPrizes = 0.0;
            List<Prize> prs = challengeId2PrizeMap.get(Integer.valueOf(challenge.getId().toString()));

            MemberPrize memberPrize = new MemberPrize();
            memberPrize.setDrPoints(challenge.getDrPoints());
            if (!isNull(prs)) {
                for (Prize prize : prs) {
                    totalPrizes += prize.getPrizeAmount() * prize.getNumberOfPrize();
                    if (prize.getPrizeType().equals(CHALLENGE_PRIZE_TYPE)) {
                        prize.setNumberOfPrize(null);
                        challengePrizes.add(prize);
                    } else if (prize.getPrizeType().equals(CHECKPOINT_PRIZE_TYPE)) {
                        prize.setPlacement(null);
                        checkPointPrizes.add(prize);
                    } else {
                        // Eliminate any other type prizes from total prizes.
                        totalPrizes -= prize.getPrizeAmount() * prize.getNumberOfPrize();
                    }
                }
                memberPrize.setPrizes(challengePrizes);
                memberPrize.setCheckPointPrizes(checkPointPrizes);
            }
            memberPrize.setTotalPrize(totalPrizes);


            challenge.setPrizes(memberPrize.getPrizes());
            challenge.setCheckPointPrizes(memberPrize.getCheckPointPrizes());
            challenge.setTotalPrize(memberPrize.getTotalPrize());
        }
    }

    /**
     * Validate input parameters for my created challenges.
     *
     * @param userId the current user Id
     * @param query  the query.
     * @throws BadRequestException if the any of the query parameters are invalid
     * @throws IOException         When an error occurs while getting user handle of logged in user
     */
    private void validateQuery(Integer userId, QueryParameter query) throws IOException {
        FilterParameter filter = query.getFilter();
        if (filter.contains("type")) {
            List<String> values = parseFilterValueToValueList(filter.get("type"), true, LOG);
            if (!ALLOWED_TYPE.containsAll(values)) {
                throw new BadRequestException("Invalid type. One of [\"active\", \"past\", \"draft\"] expected.");
            }
        }

        if (filter.contains("creator")) {
            List<String> values = parseFilterValueToValueList(filter.get("creator"), false, LOG);
            // we currently only allow the current user for this filter
            // http://apps.topcoder.com/forums/?module=Thread&threadID=828530&start=0
            String currentHandle = userDAO.getUserHandle(userId);
            for (String handle : values) {
                if (!currentHandle.equals(handle)) {
                    throw new BadRequestException("Invalid creator, only current user is supported.");
                }
            }
        }

        if (filter.contains("directProjectId")) {
            List<String> values = parseFilterValueToValueList(filter.get("directProjectId"), false, LOG);
            for (String val : values) {
                try {
                    int id = Integer.parseInt(val);
                    if (id <= 0) {
                        throw new BadRequestException("Direct Project Id should be positive.");
                    }
                } catch (NumberFormatException nfe) {
                    throw new BadRequestException("Invalid directProjectId.");
                }
            }
        }

        if (filter.contains("clientId")) {
            List<String> values = parseFilterValueToValueList(filter.get("clientId"), false, LOG);
            for (String val : values) {
                try {
                    Integer.parseInt(val);
                } catch (NumberFormatException nfe) {
                    throw new BadRequestException("Invalid clientId.");
                }
            }
        }

        if (filter.contains("billingId")) {
            List<String> values = parseFilterValueToValueList(filter.get("billingId"), false, LOG);
            for (String val : values) {
                try {
                    Integer.parseInt(val);
                } catch (NumberFormatException nfe) {
                    throw new BadRequestException("Invalid billingId.");
                }
            }
        }

        if (filter.contains("startDateFrom")) {
            String startDateFrom = parseFilterValueToValueList(filter.get("startDateFrom"), true, LOG).get(0);
            try {
                DATE_FORMAT.parse(startDateFrom);
            } catch (ParseException pe) {
                throw new BadRequestException("Invalid challenge start date filter, should be MM/dd/yyyy");
            }
        }

        if (filter.contains("startDateTo")) {
            String startDateTo = parseFilterValueToValueList(filter.get("startDateTo"), true, LOG).get(0);
            try {
                DATE_FORMAT.parse(startDateTo);
            } catch (ParseException pe) {
                throw new BadRequestException("Invalid challenge start date filter, should be MM/dd/yyyy");
            }
        }

        if (filter.contains("endDateFrom")) {
            String endDateFrom = parseFilterValueToValueList(filter.get("endDateFrom"), true, LOG).get(0);
            try {
                DATE_FORMAT.parse(endDateFrom);
            } catch (ParseException pe) {
                throw new BadRequestException("Invalid challenge end date filter, should be MM/dd/yyyy");
            }
        }

        if (filter.contains("endDateTo")) {
            String endDateTo = parseFilterValueToValueList(filter.get("endDateTo"), true, LOG).get(0);
            try {
                DATE_FORMAT.parse(endDateTo);
            } catch (ParseException pe) {
                throw new BadRequestException("Invalid challenge end date filter, should be MM/dd/yyyy");
            }
        }

        // check limits, technically this should be done by the framework since it built the query object
        // but for compatibility with the existing code, we perform some checks for now
        LimitQuery limitQuery = query.getLimitQuery();
        if (limitQuery != null) {
            if (limitQuery.getLimit() != null) {
                if (limitQuery.getLimit() == 0 || limitQuery.getLimit() < -1) {
                    throw new BadRequestException("Invalid limit, -1 if you want to get all records.");
                }
            }
            if (limitQuery.getOffset() != null) {
                if (limitQuery.getOffset() < 0) {
                    throw new BadRequestException("Invalid offset, must be 0 or more.");
                }
            }
        }
    }

    /**
     * Get the challenge filters to be used in the query and set the appropriate sql parameters as well.
     *
     * @param identity      the current user
     * @param query         The filters and limits to be used in the query.
     * @param sqlParameters The sql parameters object that will be used when execute query.
     * @return The list of filter content that need to add into query manually.
     * @throws IOException If something went wrong when read the query.
     */
    private List<String> getCreatorChallengeFilters(DirectAuthenticationToken identity, QueryParameter query,
                                                    Map<String, Object> sqlParameters) throws IOException {
        List<String> filterToAdd = new ArrayList<String>();

        filterToAdd.add(CREATOR_FILTER);
        sqlParameters.put("creator_id", identity.getUserId());

        filterToAdd.addAll(getMyChallengeFilters(query, sqlParameters));
        return filterToAdd;
    }

    /**
     * Get the challenge filters to be used in the my challenges query and set the appropriate sql parameters as well.
     *
     * @param query         The filters and limits to be used in the query.
     * @param sqlParameters The sql parameters object that will be used when execute query.
     * @return The list of filter content that need to add into query manually.
     * @throws IOException If something went wrong when read the query.
     */
    private List<String> getMyChallengeFilters(QueryParameter query,
                                               Map<String, Object> sqlParameters) throws IOException {

        FilterParameter filter = query.getFilter();
        List<String> filterToAdd = new ArrayList<String>();

        if (filter.contains("challengeType")) {
            // Get the challenge type id and insert into sqlParameters.
            List<String> challengeTypes = parseFilterValueToValueList(filter.get("challengeType"), false, LOG);
            List<Integer> challengeTypeIds = catalogDAO.getIds("challenge_type", challengeTypes, "challenge_types");
            if (challengeTypeIds.isEmpty()) {
                challengeTypeIds = NO_MATCHED_LOOKUP;
            }

            sqlParameters.put("challenge_type_ids", challengeTypeIds);
            filterToAdd.add(CHALLENGE_TYPE_FILTER);
        }

        if (filter.contains("challengeStatus")) {
            List<String> status = parseFilterValueToValueList(filter.get("challengeStatus"), true, LOG);
            if (!status.isEmpty()) {
                List<String> csFilters = new ArrayList<String>();
                int index = 0;
                for (String filterValue : status) {
                    boolean isNumber = true;
                    Integer challengeStatusId = 0;
                    try {
                        challengeStatusId = Integer.valueOf(filterValue.toString());
                    } catch (NumberFormatException nef) {
                        // Do nothing.
                        isNumber = false;
                    }
                    if (isNumber) {
                        sqlParameters.put("challenge_status_id" + index, challengeStatusId);
                        csFilters.add(" p.project_status_id = :challenge_status_id" + index);
                    } else {
                        sqlParameters.put("challenge_status_name" + index, "%" + filterValue + "%");
                        csFilters.add(" LOWER(psl.name) LIKE :challenge_status_name" + index);
                    }
                    index++;
                }
                filterToAdd.add("AND (" + StringUtils.join(csFilters, " OR ") + ") \n");
            }
        }

        if (filter.contains("type")) {
            List<String> type = parseFilterValueToValueList(filter.get("type"), true, LOG);
            List<Integer> typeIds = new ArrayList<Integer>();
            if (type.contains(ACTIVE_PROJECT_STATUS)) {
                typeIds.add(1);
            }
            if (type.contains(DRAFT_PROJECT_STATUS)) {
                typeIds.add(2);
            }
            if (type.contains("past")) {
                typeIds.addAll(catalogDAO.getIds("draft_project_status", null, null));
            }
            if (typeIds.isEmpty()) {
                typeIds = NO_MATCHED_LOOKUP;
            }
            sqlParameters.put("type_id", typeIds);
            filterToAdd.add(TYPE_FILTER);
        }

        if (filter.contains("challengeTechnologies")) {
            List<String> technologies = parseFilterValueToValueList(filter.get("challengeTechnologies"), true, LOG);
            List<Integer> technologyIds = catalogDAO.getIds("technology", technologies, "technologies");
            if (technologyIds.isEmpty()) {
                technologyIds = NO_MATCHED_LOOKUP;
            }

            sqlParameters.put("technology_ids", technologyIds);
            filterToAdd.add(TECHNOLOGY_FILTER);
        }

        if (filter.contains("challengePlatforms")) {
            List<String> platforms = parseFilterValueToValueList(filter.get("challengePlatforms"), true, LOG);
            List<Integer> platformsIds = catalogDAO.getIds("platform", platforms, "platforms");
            if (platformsIds.isEmpty()) {
                platformsIds = NO_MATCHED_LOOKUP;
            }
            // Get platform id and insert them to sqlParameters.
            sqlParameters.put("platform_ids", platformsIds);
            filterToAdd.add(PLATFORM_FILTER);
        }

        if (filter.contains("directProjectId")) {
            List<String> values = parseFilterValueToValueList(filter.get("directProjectId"), true, LOG);
            List<Integer> projectIds = new ArrayList<Integer>();
            for (String id : values) {
                projectIds.add(Integer.valueOf(id));
            }
            sqlParameters.put("direct_project_ids", projectIds);
            filterToAdd.add(DIRECT_PROJECT_ID_FILTER);
        }

        if (filter.contains("directProjectName")) {
            List<String> values = parseFilterValueToValueList(filter.get("directProjectName"), true, LOG);
            if (!values.isEmpty()) {
                List<String> pnameFilters = new ArrayList<String>();
                int index = 0;
                for (String value : values) {
                    sqlParameters.put("direct_project_name" + index, "%" + value + "%");
                    pnameFilters.add("EXISTS (SELECT 1 FROM tc_direct_project tdp "
                            + "WHERE tdp.project_id = p.tc_direct_project_id AND LOWER(tdp.name) "
                            + "LIKE (:direct_project_name" + index + "))");
                    index++;
                }
                filterToAdd.add("AND (" + StringUtils.join(pnameFilters, " OR ") + ") \n");
            }
        }

        if (filter.contains("clientId")) {
            List<String> values = parseFilterValueToValueList(filter.get("clientId"), true, LOG);
            List<Integer> clientIds = new ArrayList<Integer>();
            for (String id : values) {
                clientIds.add(Integer.valueOf(id));
            }
            sqlParameters.put("client_ids", clientIds);
            filterToAdd.add(CLIENT_ID_FILTER);
        }

        if (filter.contains("billingId")) {
            List<String> values = parseFilterValueToValueList(filter.get("billingId"), true, LOG);
            List<Integer> billingIds = new ArrayList<Integer>();
            for (String id : values) {
                billingIds.add(Integer.valueOf(id));
            }
            sqlParameters.put("billing_ids", billingIds);
            filterToAdd.add(BILLING_ID_FILTER);
        }


        try {

            boolean startDateFilterAdded = false;

            if (filter.contains("startDateFrom")) {
                String strDateFrom = parseFilterValueToValueList(filter.get("startDateFrom"), true, LOG).get(0);

                sqlParameters.put("startDateFrom", DATE_FORMAT.parse(strDateFrom));

                if (!filter.contains("startDateTo")) {
                    // there is no upper bound, use the largest date as upper bound
                    sqlParameters.put("startDateTo", MAX_DATE);
                }

                // if filter not added, add it
                if (!startDateFilterAdded) {
                    filterToAdd.add(START_DATE_FILTER);
                    startDateFilterAdded = true;
                }

            }

            if (filter.contains("startDateTo")) {
                String startDateTo = parseFilterValueToValueList(filter.get("startDateTo"), true, LOG).get(0);

                sqlParameters.put("startDateTo", processEndDate(DATE_FORMAT.parse(startDateTo)));

                if (!filter.contains("startDateFrom")) {
                    // there is no lower bound, use the min date as lower bound
                    sqlParameters.put("startDateFrom", MIN_DATE);
                }

                // if filter not added, add it
                if (!startDateFilterAdded) {
                    filterToAdd.add(START_DATE_FILTER);
                    startDateFilterAdded = true;
                }

            }
        } catch (ParseException pe) {
            throw new IOException("Error when read / parse the start date filter", pe);
        }

        try {

            boolean endDateFilterAdded = false;

            if (filter.contains("endDateFrom")) {
                String endDateFrom = parseFilterValueToValueList(filter.get("endDateFrom"), true, LOG).get(0);

                sqlParameters.put("endDateFrom", DATE_FORMAT.parse(endDateFrom));

                if (!filter.contains("endDateTo")) {
                    // there is no upper bound, use the largest date as upper bound
                    sqlParameters.put("endDateTo", MAX_DATE);
                }

                // if filter not added, add it
                if (!endDateFilterAdded) {
                    filterToAdd.add(END_DATE_FILTER);
                    endDateFilterAdded = true;
                }

            }

            if (filter.contains("endDateTo")) {
                String endDateTo = parseFilterValueToValueList(filter.get("endDateTo"), true, LOG).get(0);

                sqlParameters.put("endDateTo", processEndDate(DATE_FORMAT.parse(endDateTo)));

                if (!filter.contains("endDateFrom")) {
                    // there is no lower bound, use the min date as lower bound
                    sqlParameters.put("endDateFrom", MIN_DATE);
                }

                // if filter not added, add it
                if (!endDateFilterAdded) {
                    filterToAdd.add(END_DATE_FILTER);
                    endDateFilterAdded = true;
                }

            }
        } catch (ParseException pe) {
            throw new IOException("Error when read / parse the start date filter", pe);
        }


        return filterToAdd;
    }

    /**
     * Helper method to process the end date to set its time fragmentation to 23:59:59
     *
     * @param endDate
     * @return the processed end date.
     * @since 1.1
     */
    private Date processEndDate(Date endDate) {
        Calendar c = Calendar.getInstance();
        c.setTime(endDate);

        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);

        return c.getTime();
    }

    /**
     * Gets the metadata
     *
     * @param request the http servlet request.
     * @param query   the QueryParameter instance.
     * @return the metadata
     * @throws Exception if any error.
     */
    @Override
    public Metadata getMetadata(HttpServletRequest request, QueryParameter query) throws Exception {
        CountableMetadata metadata = new CountableMetadata();

        DirectAuthenticationToken identity = SecurityUtil.getAuthentication(request);
        identity.authorize(AccessLevel.ADMIN, AccessLevel.MEMBER);

        try {
            Map<String, Object> sqlParameters = new HashMap<String, Object>();
            List<String> customFilter;

            // validate filters
            validateQuery(identity.getUserId(), query);

            // for access checking
            sqlParameters.put("user_id", identity.getUserId());

            if (query.getFilter().contains("creator")) {
                // Perform "My created challenges" flow
                customFilter = getCreatorChallengeFilters(identity, query, sqlParameters);
                sqlParameters.put("creator_id", identity.getUserId());
            } else {
                customFilter = getMyChallengeFilters(query, sqlParameters);
            }

            Integer myChallengesCount = challengeDAO.getMyChallengesCount(customFilter, sqlParameters);

            if (myChallengesCount != null) {
                metadata.setTotalCount(myChallengesCount);
            }
        } catch (IOException e) {
            throw new ServerInternalException("An error occurred while querying for challenges total count", e);
        }

        return metadata;
    }
}
