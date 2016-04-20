/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.core.documents;

import static org.apache.openmeetings.util.OpenmeetingsVariables.webAppRootKey;
import static org.apache.openmeetings.core.documents.CreateLibraryPresentation.generateXMLDocument;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;

import org.apache.commons.transaction.util.FileHelper;
import org.apache.openmeetings.core.converter.GenerateSWF;
import org.apache.openmeetings.core.converter.GenerateThumbs;
import org.apache.openmeetings.db.dao.basic.ConfigurationDao;
import org.apache.openmeetings.util.OmFileHelper;
import org.apache.openmeetings.util.process.ConverterProcessResult;
import org.apache.openmeetings.util.process.ConverterProcessResultList;
import org.apache.openmeetings.util.process.ProcessHelper;
import org.red5.logging.Red5LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

public class GeneratePDF {
	private static final Logger log = Red5LoggerFactory.getLogger(GeneratePDF.class, webAppRootKey);

	@Autowired
	private GenerateThumbs generateThumbs;
	@Autowired
	private GenerateSWF generateSWF;
	@Autowired
	private ConfigurationDao configurationDao;

	public ConverterProcessResultList convertPDF(String hash, String roomName, boolean fullProcessing, File inFile) throws Exception {
		String inFileName = inFile.getName();
		ConverterProcessResultList returnError = new ConverterProcessResultList();

		File fileFullPath = new File(OmFileHelper.getUploadTempRoomDir(roomName), inFileName);
		File destinationFolder = OmFileHelper.getNewDir(OmFileHelper.getUploadRoomDir(roomName), hash);

		log.debug("fullProcessing: " + fullProcessing);
		if (fullProcessing) {
			ConverterProcessResult processOpenOffice = doJodConvert(fileFullPath, destinationFolder, hash);
			returnError.addItem("processOpenOffice", processOpenOffice);
			ConverterProcessResult processThumb = generateThumbs.generateBatchThumb(new File(destinationFolder, hash + ".pdf"), destinationFolder, 80, "thumb");
			returnError.addItem("processThumb", processThumb);
			ConverterProcessResult processSWF = generateSWF.generateSwf(destinationFolder, destinationFolder, hash);
			returnError.addItem("processSWF", processSWF);
		} else {
			log.debug("-- generateBatchThumb --");

			ConverterProcessResult processThumb = generateThumbs.generateBatchThumb(fileFullPath, destinationFolder, 80, "thumb");
			returnError.addItem("processThumb", processThumb);

			ConverterProcessResult processSWF = generateSWF.generateSwf(fileFullPath.getParentFile(), destinationFolder, hash);
			returnError.addItem("processSWF", processSWF);
		}

		// now it should be completed so copy that file to the expected location
		File fileWhereToMove = new File(destinationFolder, inFileName);
		fileWhereToMove.createNewFile();
		FileHelper.moveRec(inFile, fileWhereToMove);

		if (fullProcessing) {
			ConverterProcessResult processXML = generateXMLDocument(destinationFolder, inFileName, hash + ".pdf", hash + ".swf");
			returnError.addItem("processXML", processXML);
		} else {
			ConverterProcessResult processXML = generateXMLDocument(destinationFolder, inFileName, null, hash + ".swf");
			returnError.addItem("processXML", processXML);
		}

		return returnError;
	}

	/**
	 * Generates PDF using JOD Library (external library)
	 */
	public ConverterProcessResult doJodConvert(File fileFullPath, File destinationFolder, String outputfile) {
		try {
			String jodPath = configurationDao.getConfValue("jod.path", String.class, "./jod");
			String officePath = configurationDao.getConfValue("office.path", String.class, "");

			File jodFolder = new File(jodPath);
			if (!jodFolder.exists() || !jodFolder.isDirectory()) {
				throw new Exception("Path to JOD Library folder does not exist");
			}

			ArrayList<String> argv = new ArrayList<String>();
			argv.add("java");

			if (officePath.trim().length() > 0) {
				argv.add("-Doffice.home=" + officePath);
			}
			String jodConverterJar = "";

			String[] list = jodFolder.list(new FilenameFilter() {
				@Override
				public boolean accept(File file1, String name) {
					return name.endsWith(".jar");
				}
			});
			if (list != null) {
				for (String jar : list) {
					argv.add("-cp");
					if (jar.startsWith("jodconverter")) {
						jodConverterJar = jar;
					}
					argv.add(new File(jodFolder, jar).getCanonicalPath());
				}
			}
			if (jodConverterJar.length() == 0) {
				throw new Exception("Could not find jodConverter JAR file in JOD folder");
			}

			argv.add("-jar");
			argv.add(new File(jodFolder, jodConverterJar).getCanonicalPath());
			argv.add(fileFullPath.getCanonicalPath());
			argv.add(new File(destinationFolder, outputfile + ".pdf").getCanonicalPath());

			return ProcessHelper.executeScript("doJodConvert", argv.toArray(new String[argv.size()]));

		} catch (Exception ex) {
			log.error("doJodConvert", ex);
			return new ConverterProcessResult("doJodConvert", ex.getMessage(), ex);
		}
	}
}
