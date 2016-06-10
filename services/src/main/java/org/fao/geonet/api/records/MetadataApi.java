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

import org.apache.commons.io.FileUtils;
import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.api.API;
import org.fao.geonet.api.ApiUtils;
import org.fao.geonet.api.records.model.related.RelatedItemType;
import org.fao.geonet.api.records.model.related.RelatedResponse;
import org.fao.geonet.api.tools.i18n.LanguageUtils;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.ReservedOperation;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.GeonetworkDataDirectory;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.kernel.mef.MEFLib;
import org.fao.geonet.lib.Lib;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.Xml;
import org.jdom.Attribute;
import org.jdom.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import jeeves.constants.Jeeves;
import jeeves.server.context.ServiceContext;
import jeeves.services.ReadWriteController;

import static org.fao.geonet.kernel.mef.MEFLib.Version.Constants.MEF_V1_ACCEPT_TYPE;
import static org.fao.geonet.kernel.mef.MEFLib.Version.Constants.MEF_V2_ACCEPT_TYPE;

@RequestMapping(value = {
    "/api/records",
    "/api/" + API.VERSION_0_1 +
        "/records"
})
@Api(value = "records",
    tags = "records",
    description = "Metadata record operations")
@Controller("records")
@ReadWriteController
public class MetadataApi implements ApplicationContextAware {

    @Autowired
    SchemaManager _schemaManager;

    @Autowired
    LanguageUtils languageUtils;

    private ApplicationContext context;

    public synchronized void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }


    @ApiOperation(value = "Get a metadata record",
        notes = "Depending on the accept header the appropriate formatter is used. " +
            "When requesting a ZIP, a MEF version 2 file is returned. " +
            "When requesting HTML, the default formatter is used.",
        nickname = "get")
    @RequestMapping(value = "/{metadataUuid}",
        method = RequestMethod.GET,
        consumes = {
            MediaType.ALL_VALUE
        },
        produces = {
            MediaType.TEXT_HTML_VALUE,
            MediaType.APPLICATION_XML_VALUE,
            MediaType.APPLICATION_XHTML_XML_VALUE,
            MediaType.APPLICATION_JSON_VALUE,
            "application/pdf",
            "application/zip",
            MEF_V1_ACCEPT_TYPE,
            MEF_V2_ACCEPT_TYPE
        })
    public
    @ResponseBody
    void getRecord(
        @ApiParam(value = "Record UUID.",
            required = true)
        @PathVariable
            String metadataUuid,
        @ApiParam(value = "Accept header should indicate which is the appropriate format " +
            "to return. It could be text/html, application/xml, application/zip, ..." +
            "If no appropriate Accept header found, the XML format is returned.",
            required = true)
        @RequestHeader(
            value = HttpHeaders.ACCEPT,
            defaultValue = MediaType.APPLICATION_XML_VALUE,
            required = false
        )
            String acceptHeader,
        HttpServletResponse response,
        HttpServletRequest request
    )
        throws Exception {
        ApiUtils.canViewRecord(metadataUuid, request);

        List<String> accept = Arrays.asList(acceptHeader.split(","));

        String defaultFormatter = "xsl-view";
        if (accept.contains(MediaType.TEXT_HTML_VALUE) ||
            accept.contains(MediaType.APPLICATION_XHTML_XML_VALUE) ||
            accept.contains("application/pdf")) {
            response.sendRedirect(metadataUuid + "/formatters/" + defaultFormatter);
        } else if (
            accept.contains(MediaType.APPLICATION_XML_VALUE) ||
            accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            response.sendRedirect(metadataUuid + "/formatters/xml");
        } else if (
            accept.contains("application/zip") ||
                accept.contains(MEF_V1_ACCEPT_TYPE) ||
                accept.contains(MEF_V2_ACCEPT_TYPE)) {
            response.setHeader(HttpHeaders.ACCEPT, MEF_V2_ACCEPT_TYPE);
            response.sendRedirect(metadataUuid + "/formatters/zip");
        } else {
            response.setHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_XHTML_XML_VALUE);
            response.sendRedirect(metadataUuid + "/formatters/" + defaultFormatter);
        }
    }


    @ApiOperation(value = "Get a metadata record as XML",
        notes = "",
        nickname = "getRecordAsXml")
    @RequestMapping(value =
        {
            "/{metadataUuid}/formatters/xml",
            "/{metadataUuid}/formatters/json"
        },
        method = RequestMethod.GET,
        produces = {
            MediaType.APPLICATION_XML_VALUE,
            MediaType.APPLICATION_JSON_VALUE
        })
    public
    @ResponseBody
    Object getRecordAsXML(
        @ApiParam(value = "Record UUID.",
            required = true)
        @PathVariable
            String metadataUuid,
        @ApiParam(value = "Add XSD schema location based on standard configuration " +
            "(see schema-ident.xml).",
            required = false)
        @RequestParam(required = false, defaultValue = "true")
            boolean addSchemaLocation,
        @ApiParam(value = "Increase record popularity",
            required = false)
        @RequestParam(required = false, defaultValue = "true")
            boolean increasePopularity,
        @RequestHeader(
            value = HttpHeaders.ACCEPT,
            defaultValue = MediaType.APPLICATION_XML_VALUE
        )
            String acceptHeader,
        HttpServletResponse response,
        HttpServletRequest request
    )
        throws Exception {
        ApplicationContext appContext = ApplicationContextHolder.get();
        DataManager dataManager = appContext.getBean(DataManager.class);
        Metadata metadata = ApiUtils.canViewRecord(metadataUuid, request);

        ServiceContext context = ApiUtils.createServiceContext(request);
        try {
            Lib.resource.checkPrivilege(context,
                String.valueOf(metadata.getId()),
                ReservedOperation.view);
        } catch (Exception e) {
            // TODO: i18n
            // TODO: Report exception in JSON format
            throw new SecurityException(String.format(
                "Metadata with UUID '%s' is not shared with you.",
                metadataUuid
            ));
        }

        if (increasePopularity) {
            dataManager.increasePopularity(context, metadata.getId() + "");
        }

        Element xml = metadata.getXmlData(false);
        if (addSchemaLocation) {
            Attribute schemaLocAtt = _schemaManager.getSchemaLocation(
                metadata.getDataInfo().getSchemaId(), context);

            if (schemaLocAtt != null) {
                if (xml.getAttribute(
                    schemaLocAtt.getName(),
                    schemaLocAtt.getNamespace()) == null) {
                    xml.setAttribute(schemaLocAtt);
                    // make sure namespace declaration for schemalocation is present -
                    // remove it first (does nothing if not there) then add it
                    xml.removeNamespaceDeclaration(schemaLocAtt.getNamespace());
                    xml.addNamespaceDeclaration(schemaLocAtt.getNamespace());
                }
            }
        }

        boolean isJson = acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE);

        response.setHeader("Content-Disposition", String.format(
            "inline; filename=\"%s.%s\"",
            metadata.getUuid(),
            isJson ? "json" : "xml"
        ));
        return isJson ? Xml.getJSON(xml) : xml;
    }

    @ApiOperation(
        value = "Get a metadata record as ZIP",
        notes = "Metadata Exchange Format (MEF) is returned. MEF is a ZIP file containing " +
            "the metadata as XML and some others files depending on the version requested. " +
            "See http://geonetwork-opensource.org/manuals/trunk/eng/users/annexes/mef-format.html.",
        nickname = "getRecordAsZip")
    @RequestMapping(value = "/{metadataUuid}/formatters/zip",
        method = RequestMethod.GET,
        consumes = {
            MediaType.ALL_VALUE
        },
        produces = {
            "application/zip"
        })
    public
    @ResponseBody
    void getRecordAsZip(
        @ApiParam(
            value = "Record UUID.",
            required = true)
        @PathVariable
            String metadataUuid,
        @ApiParam(
            value = "MEF file format.",
            required = true)
        @RequestParam(
            required = false,
            defaultValue = "full")
            MEFLib.Format format,
        @ApiParam(
            value = "With related records (parent and service).",
            required = false)
        @RequestParam(
            required = false,
            defaultValue = "true")
            boolean withRelated,
        @ApiParam(
            value = "Resolve XLinks in the records.",
            required = false)
        @RequestParam(
            required = false,
            defaultValue = "true")
            boolean withXLinksResolved,
        @ApiParam(
            value = "Preserve XLink URLs in the records.",
            required = false)
        @RequestParam(
            required = false,
            defaultValue = "false")
            boolean withXLinkAttribute,
        @RequestHeader(
            value = HttpHeaders.ACCEPT,
            defaultValue = "application/x-gn-mef-2-zip"
        )
            String acceptHeader,
        HttpServletResponse response,
        HttpServletRequest request
    )
        throws Exception {
        ApplicationContext appContext = ApplicationContextHolder.get();
        GeonetworkDataDirectory dataDirectory = appContext.getBean(GeonetworkDataDirectory.class);
        Metadata metadata = ApiUtils.canViewRecord(metadataUuid, request);
        Path stylePath = dataDirectory.getWebappDir().resolve(Geonet.Path.SCHEMAS);
        Path file = null;
        ServiceContext context = ApiUtils.createServiceContext(request);
        MEFLib.Version version = MEFLib.Version.find(acceptHeader);
        if (version == MEFLib.Version.V1) {
            // This parameter is deprecated in v2.
            boolean skipUUID = false;
            file = MEFLib.doExport(
                context, metadataUuid, format.toString(),
                skipUUID, withXLinksResolved, withXLinkAttribute
            );
        } else {
            Set<String> tmpUuid = new HashSet<String>();
            tmpUuid.add(metadataUuid);
            // MEF version 2 support multiple metadata record by file.
            if (withRelated) {
                // Adding children in MEF file

                // Creating request for services search
                Element childRequest = new Element("request");
                childRequest.addContent(new Element("parentUuid").setText(metadataUuid));
                childRequest.addContent(new Element("to").setText("1000"));

                // Get children to export - It could be better to use GetRelated service TODO
                Set<String> childs = MetadataUtils.getUuidsToExport(
                    metadataUuid, request, childRequest);
                if (childs.size() != 0) {
                    tmpUuid.addAll(childs);
                }

                // Creating request for services search
                Element servicesRequest = new Element(Jeeves.Elem.REQUEST);
                servicesRequest.addContent(new Element(
                    org.fao.geonet.constants.Params.OPERATES_ON)
                    .setText(metadataUuid));
                servicesRequest.addContent(new Element(
                    org.fao.geonet.constants.Params.TYPE)
                    .setText("service"));

                // Get linked services for export
                Set<String> services = MetadataUtils.getUuidsToExport(
                    metadataUuid, request, servicesRequest);
                if (services.size() != 0) {
                    tmpUuid.addAll(services);
                }
            }
            Log.info(Geonet.MEF, "Building MEF2 file with " + tmpUuid.size()
                + " records.");

            file = MEFLib.doMEF2Export(context, tmpUuid, format.toString(), false, stylePath, withXLinksResolved, withXLinkAttribute);
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, String.format(
            "inline; filename=\"%s.zip\"",
            metadata.getUuid()
        ));
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(Files.size(file)));
        FileUtils.copyFile(file.toFile(), response.getOutputStream());
    }


    @ApiOperation(
        value = "Get record related resources",
        nickname = "get",
        notes = "Retrieve related services, datasets, onlines, thumbnails, sources, ... " +
            "to this records.")
    @RequestMapping(value = "/{metadataUuid}/related",
        method = RequestMethod.GET,
        produces = {
            MediaType.APPLICATION_XML_VALUE,
            MediaType.APPLICATION_JSON_VALUE
        })
    @ResponseBody
    public RelatedResponse getRelated(
        @ApiParam(value = "Record UUID.",
            required = true)
        @PathVariable
            String metadataUuid,
        @ApiParam(value = "Type of related resource. If none, all resources are returned.",
            required = false
        )
        @RequestParam(defaultValue = "")
            RelatedItemType[] type,
        @ApiParam(value = "Start offset for paging. Default 1. Only applies to related metadata records (ie. not for thumbnails).",
            required = false
        )
        @RequestParam(defaultValue = "1")
            int start,
        @ApiParam(value = "Number of rows returned. Default 100.")
        @RequestParam(defaultValue = "100")
            int rows,
        HttpServletRequest request) throws Exception {

        Metadata md = ApiUtils.canViewRecord(metadataUuid, request);

        Locale language = languageUtils.parseAcceptLanguage(request.getLocales());

        // TODO PERF: ByPass XSL processing and create response directly
        // At least for related metadata and keep XSL only for links
        final ServiceContext context = ApiUtils.createServiceContext(request);
        Element raw = new Element("root").addContent(Arrays.asList(
            new Element("gui").addContent(Arrays.asList(
                new Element("language").setText(language.getISO3Language()),
                new Element("url").setText(context.getBaseUrl())
            )),
            MetadataUtils.getRelated(context, md.getId(), md.getUuid(), type, start, start + rows, true)
        ));
        GeonetworkDataDirectory dataDirectory = context.getBean(GeonetworkDataDirectory.class);
        Path relatedXsl = dataDirectory.getWebappDir().resolve("xslt/services/metadata/relation.xsl");

        final Element transform = Xml.transform(raw, relatedXsl);
        RelatedResponse response = (RelatedResponse) Xml.unmarshall(transform, RelatedResponse.class);
        return response;
    }
}
