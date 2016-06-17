/*
 * Copyright (C) 2001-2016 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */

package org.fao.geonet.api.records;

import com.google.common.base.Optional;

import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.api.API;
import org.fao.geonet.api.ApiParams;
import org.fao.geonet.api.ApiUtils;
import org.fao.geonet.api.exception.ResourceNotFoundException;
import org.fao.geonet.api.processing.report.MetadataProcessingReport;
import org.fao.geonet.api.processing.report.SimpleMetadataProcessingReport;
import org.fao.geonet.api.records.model.PrivilegeParameter;
import org.fao.geonet.api.records.model.SharingParameter;
import org.fao.geonet.api.tools.i18n.LanguageUtils;
import org.fao.geonet.domain.Group;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.OperationAllowed;
import org.fao.geonet.domain.OperationAllowedId;
import org.fao.geonet.domain.Profile;
import org.fao.geonet.domain.ReservedGroup;
import org.fao.geonet.domain.ReservedOperation;
import org.fao.geonet.kernel.AccessManager;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.repository.GroupRepository;
import org.fao.geonet.repository.MetadataCategoryRepository;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import jeeves.services.ReadWriteController;
import springfox.documentation.annotations.ApiIgnore;

import static org.fao.geonet.api.ApiParams.API_CLASS_RECORD_OPS;
import static org.fao.geonet.api.ApiParams.API_CLASS_RECORD_TAG;
import static org.fao.geonet.api.ApiParams.API_PARAM_RECORD_UUID;
import static org.fao.geonet.repository.specification.OperationAllowedSpecs.hasGroupId;
import static org.fao.geonet.repository.specification.OperationAllowedSpecs.hasMetadataId;
import static org.springframework.data.jpa.domain.Specifications.where;

@RequestMapping(value = {
    "/api/records",
    "/api/" + API.VERSION_0_1 +
        "/records"
})
@Api(value = API_CLASS_RECORD_TAG,
    tags = API_CLASS_RECORD_TAG,
    description = API_CLASS_RECORD_OPS)
@Controller("recordSharing")
@ReadWriteController
public class MetadataSharingApi {

    @Autowired
    LanguageUtils languageUtils;


    @ApiOperation(
        value = "Set record sharing",
        notes = "Privileges are assigned by group. User needs to be able " +
            "to edit a record to set sharing settings. For reserved group " +
            "(ie. Internet, Intranet & Guest), user MUST be reviewer of one group. " +
            "For other group, if Only set privileges to user's groups is set " +
            "in catalog configuration user MUST be a member of the group.<br/>" +
            "Clear first allows to unset all operations first before setting the new ones." +
            "Clear option does not remove reserved groups operation if user is not an " +
            "administrator, a reviewer or the owner of the record",
        nickname = "share")
    @RequestMapping(value = "/{metadataUuid}/sharing",
        method = RequestMethod.PUT
    )
    public
    @ResponseBody
    ResponseEntity share(
        @ApiParam(
            value = API_PARAM_RECORD_UUID,
            required = true)
        @PathVariable
            String metadataUuid,
        @ApiParam(
            value = "Privileges",
            required = true
        )
        @RequestBody(
            required = true
        )
            SharingParameter sharing,
        @ApiIgnore
        @ApiParam(hidden = true)
            HttpSession session,
        HttpServletRequest request
    )
        throws Exception {
        Metadata metadata = ApiUtils.canEditRecord(metadataUuid, request);
        ApplicationContext appContext = ApplicationContextHolder.get();
        ServiceContext context = ApiUtils.createServiceContext(request);

        boolean skip = false;

        //--- in case of owner, privileges for groups 0,1 and GUEST are disabled
        //--- and are not sent to the server. So we cannot remove them
        UserSession us = ApiUtils.getUserSession(session);
        boolean isAdmin = Profile.Administrator == us.getProfile();
        boolean isReviewer = Profile.Reviewer == us.getProfile();
        if (us.getUserIdAsInt() == metadata.getSourceInfo().getOwner() &&
            !isAdmin &&
            !isReviewer) {
            skip = true;
        }

        DataManager dataManager = appContext.getBean(DataManager.class);
        if (sharing.isClear()) {
            dataManager.deleteMetadataOper(context, String.valueOf(metadata.getId()), skip);
        }

        List<PrivilegeParameter> privileges = sharing.getPrivileges();
        if (privileges != null) {
            for (PrivilegeParameter p : privileges) {
                // Never set editing for reserved group
                if (p.getOperation() == ReservedOperation.editing.getId() &&
                    ReservedGroup.isReserved(p.getGroup())) {
                    continue;
                }

                if (p.isPublished()) {
                    dataManager.setOperation(context, metadata.getId(), p.getGroup(), p.getOperation());
                } else if (!sharing.isClear() && !p.isPublished()) {
                    dataManager.unsetOperation(context, metadata.getId(), p.getGroup(), p.getOperation());
                }
            }
        }
        dataManager.indexMetadata(String.valueOf(metadata.getId()), true);

        return new ResponseEntity<>(HttpStatus.CREATED);
    }




    @ApiOperation(
        value = "Set record group",
        notes = "",
        nickname = "setRecordGroup")
    @RequestMapping(value = "/{metadataUuid}/group",
        method = RequestMethod.PUT
    )
    public
    @ResponseBody
    ResponseEntity setRecordGroup(
        @ApiParam(
            value = API_PARAM_RECORD_UUID,
            required = true)
        @PathVariable
            String metadataUuid,
        @ApiParam(
            value = "Group identifier",
            required = true
        )
        @RequestBody(
            required = true
        )
            Integer groupIdentifier,
        HttpServletRequest request
    )
        throws Exception {
        Metadata metadata = ApiUtils.canEditRecord(metadataUuid, request);
        ApplicationContext appContext = ApplicationContextHolder.get();
        ServiceContext context = ApiUtils.createServiceContext(request);

        Group group = appContext.getBean(GroupRepository.class).findOne(groupIdentifier);
        if (group == null) {
            throw new ResourceNotFoundException(String.format(
                "Group with identifier '%s' not found.", groupIdentifier
            ));
        }

        DataManager dataManager = appContext.getBean(DataManager.class);
        MetadataRepository metadataRepository = appContext.getBean(MetadataRepository.class);

        metadata.getSourceInfo().setGroupOwner(groupIdentifier);
        metadataRepository.save(metadata);
        dataManager.indexMetadata(String.valueOf(metadata.getId()), true);

        return new ResponseEntity<>(HttpStatus.CREATED);
    }



    @ApiOperation(
        value = "Set group and owner for one or more records",
        notes = "",
        nickname = "setGroupAndOwner")
    @RequestMapping(value = "/group-and-owner",
        method = RequestMethod.PUT
    )
    public
    @ResponseBody
    ResponseEntity<MetadataProcessingReport> setGroupAndOwner(
        @ApiParam(value = ApiParams.API_PARAM_RECORD_UUIDS_OR_SELECTION,
            required = false)
        @RequestParam(required = false)
            String[] uuids,
        @ApiParam(
            value = "Group identifier",
            required = true
        )
        @RequestParam(
            required = true
        )
            Integer groupIdentifier,
        @ApiParam(
            value = "User identifier",
            required = true
        )
        @RequestParam(
            required = true
        )
            Integer userIdentifier,
        @ApiIgnore
        @ApiParam(hidden = true)
        HttpSession session,
        HttpServletRequest request
    )
        throws Exception {
        MetadataProcessingReport report = new SimpleMetadataProcessingReport();

        try {
            Set<String> records = ApiUtils.getUuidsParameterOrSelection(uuids, ApiUtils.getUserSession(session));
            report.setTotalRecords(records.size());

            final ApplicationContext context = ApplicationContextHolder.get();
            final DataManager dataManager = context.getBean(DataManager.class);
            final MetadataCategoryRepository categoryRepository = context.getBean(MetadataCategoryRepository.class);
            final AccessManager accessMan = context.getBean(AccessManager.class);
            final MetadataRepository metadataRepository = context.getBean(MetadataRepository.class);

            ServiceContext serviceContext = ApiUtils.createServiceContext(request);

            List<String> listOfUpdatedRecords = new ArrayList<>();
            for (String uuid : records) {
                Metadata metadata = metadataRepository.findOneByUuid(uuid);
                if (metadata == null) {
                    report.incrementNullRecords();
                } else if (!accessMan.canEdit(
                    serviceContext, String.valueOf(metadata.getId()))) {
                    report.addNotEditableMetadataId(metadata.getId());
                } else {
                    //-- Get existing owner and privileges for that owner - note that
                    //-- owners don't actually have explicit permissions - only their
                    //-- group does which is why we have an ownerGroup (parameter groupid)
                    Integer sourceUsr = metadata.getSourceInfo().getOwner();
                    Integer sourceGrp = metadata.getSourceInfo().getGroupOwner();
                    Vector<OperationAllowedId> sourcePriv =
                        retrievePrivileges(serviceContext, String.valueOf(metadata.getId()), sourceUsr, sourceGrp);

                    // -- Set new privileges for new owner from privileges of the old
                    // -- owner, if none then set defaults
                    if (sourcePriv.size() == 0) {
                        dataManager.copyDefaultPrivForGroup(
                            serviceContext,
                            String.valueOf(metadata.getId()),
                            String.valueOf(groupIdentifier),
                            false);
                        report.addMetadataInfos(metadata.getId(), String.format(
                            "No privileges for user '%s' on metadata '%s', so setting default privileges",
                            sourceUsr, metadata.getUuid()
                        ));
                    } else {
                        for (OperationAllowedId priv : sourcePriv) {
                            if (sourceGrp != null) {
                                dataManager.unsetOperation(serviceContext,
                                    metadata.getId(),
                                    sourceGrp,
                                    priv.getOperationId());
                            }
                            dataManager.setOperation(serviceContext,
                                metadata.getId(),
                                groupIdentifier,
                                priv.getOperationId());
                        }
                    }
                    // -- set the new owner into the metadata record
                    dataManager.updateMetadataOwner(metadata.getId(), String.valueOf(userIdentifier), String.valueOf(groupIdentifier));
                    report.addMetadataId(metadata.getId());
                    report.incrementProcessedRecords();
                }
            }
            dataManager.flush();
            dataManager.indexMetadata(listOfUpdatedRecords);

        } catch (Exception exception) {
            report.addError(exception);
        } finally {
            report.close();
        }

        return new ResponseEntity<>(report, HttpStatus.CREATED);
    }

    public static Vector<OperationAllowedId> retrievePrivileges(ServiceContext context, String id, Integer userId, Integer groupId) throws Exception {

        OperationAllowedRepository opAllowRepo = context.getBean(OperationAllowedRepository.class);

        int iMetadataId = Integer.parseInt(id);
        Specifications<OperationAllowed> spec =
            where(hasMetadataId(iMetadataId));
        if (groupId != null) {
            spec = spec.and(hasGroupId(groupId));
        }

        List<OperationAllowed> operationsAllowed = opAllowRepo.findAllWithOwner(userId, Optional.of((Specification<OperationAllowed>) spec));

        Vector<OperationAllowedId> result = new Vector<OperationAllowedId>();
        for (OperationAllowed operationAllowed : operationsAllowed) {
            result.add(operationAllowed.getId());
        }

        return result;
    }
}
