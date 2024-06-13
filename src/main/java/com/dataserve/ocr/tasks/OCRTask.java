package com.dataserve.ocr.tasks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import javax.security.auth.Subject;

import com.dataserve.ocr.bean.DocumentBean;
import com.dataserve.ocr.exception.CMException;
import com.dataserve.ocr.exception.OCRException;
import com.dataserve.ocr.processor.ImageFileProcessor;
import com.dataserve.ocr.processor.PDFFileProcessor;
import com.dataserve.ocr.processor.Processor;
import com.dataserve.ocr.util.Config;
import com.dataserve.ocr.util.Log;
import com.filenet.api.collection.ContentElementList;
import com.filenet.api.constants.AutoClassify;
import com.filenet.api.constants.CheckinType;
import com.filenet.api.constants.PropertyNames;
import com.filenet.api.constants.RefreshMode;
import com.filenet.api.constants.ReservationType;
import com.filenet.api.core.Connection;
import com.filenet.api.core.ContentTransfer;
import com.filenet.api.core.Document;
import com.filenet.api.core.Domain;
import com.filenet.api.core.Factory;
import com.filenet.api.core.ObjectStore;
import com.filenet.api.property.FilterElement;
import com.filenet.api.property.PropertyFilter;
import com.filenet.api.util.UserContext;

public class OCRTask implements Callable<DocumentBean>{
	private DocumentBean bean;
	private Connection conn;
	private ObjectStore os;
	
	String PDF="application/pdf";
	String IMAGES="image/";
	
	public OCRTask(DocumentBean bean) {
		this.bean = bean;
	}

	@Override
	public DocumentBean call() throws Exception {
		Log.debug(Thread.currentThread().getName() + ": started processing document " + bean.getDocumentId());
		File sourceFile = null;
		File processedFile = null;
		try {
			bean.setStatus(DocumentBean.STATUS_RUN);
			
			openCMConnection();
			Document doc = getDocument();
			List<File> attachments = getDocumentAttachment(doc);
			if (attachments.size() == 0) {
				bean.setStatus(DocumentBean.STATUS_IGNORE);
				return bean;
			}
			
			sourceFile = attachments.get(0);
			bean.setFileName(sourceFile.getName());
			bean.setStatus(DocumentBean.STATUS_FETCH);
			Log.debug(Thread.currentThread().getName() + ": attachment file " + sourceFile.getName() + " has been fetched");
			
			Processor processor = null;	
			String mimeType = doc.getProperties().get(PropertyNames.MIME_TYPE).getStringValue();
			if (mimeType.equalsIgnoreCase(PDF)) {
				processor = new PDFFileProcessor();
			} else if (mimeType.contains(IMAGES)){
				processor = new ImageFileProcessor();
			}
			
			if (processor != null) {
				bean.setStatus(DocumentBean.STATUS_OCR);
				Log.debug(Thread.currentThread().getName() + ": attachment file " + sourceFile.getAbsolutePath() + " has been processed");
			} else {
				bean.setStatus(DocumentBean.STATUS_IGNORE);
				Log.debug(Thread.currentThread().getName() + ": attachment file " + sourceFile.getAbsolutePath() + " has been ignored");
				return bean;
			}
			
			if (Config.getOutputType().toUpperCase().contains("PDF")) {
				processedFile = processor.processFile(sourceFile);
				saveAttachmentAsPDF(processedFile);
			}
			
			if (Config.getOutputType().toUpperCase().contains("TXT")) {
				StringBuilder content = processor.processFileAsText(sourceFile);
				saveAttachmentAsText(content, sourceFile, mimeType);
			}
			
			bean.setStatus(DocumentBean.STATUS_SUCCESS);
			Log.debug(Thread.currentThread().getName() + ": attachment file " + sourceFile.getName() + " has been saved");
		} catch (OCRException e) {
			Log.error("Error processing file", e);
			bean.setStatus(DocumentBean.STATUS_FAIL);
			bean.setErrorMassage(e.getMessage());
		} catch (Exception e) {
			Log.error("Error occurred while running OCR Process task", e);
			bean.setStatus(DocumentBean.STATUS_FAIL);
			bean.setErrorMassage(e.getMessage());
		} finally {
			closeCMConnection();
			if (sourceFile != null) {
				try {
					Files.deleteIfExists(sourceFile.toPath());
				} catch (Exception e) {
					Log.warn("Source file " + sourceFile.getAbsolutePath() + " couldn't be deleted", e);
				}
			}
			if (processedFile != null) {
				try {
					Files.deleteIfExists(processedFile.toPath());
				} catch (Exception e) {
					Log.warn("Processed file " + processedFile.getAbsolutePath() + " couldn't be deleted", e);
				}
			}
		}
		
		Log.debug(Thread.currentThread().getName() + ": completed processing document " + bean.getDocumentId());
		return bean;
	}

	private void openCMConnection() {
		conn = Factory.Connection.getConnection(Config.getContentEngineURI());
	    Subject subject = UserContext.createSubject(conn, Config.getContentEngineUsername(), Config.getContentEnginePassword(), null);
	    UserContext.get().pushSubject(subject);
	    Domain domain = Factory.Domain.fetchInstance(conn, null, null);
	    os = Factory.ObjectStore.fetchInstance(domain, Config.getObjectStoreName(), null);
	}
	
	private Document getDocument() throws CMException {
		// Get document and populate property cache.
		PropertyFilter pf = new PropertyFilter();
		pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.RETRIEVAL_NAME, null) );
		pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_SIZE, null) );
		pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_ELEMENTS, null) );
		pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.IS_CURRENT_VERSION, null) );
		pf.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.MIME_TYPE, null) );
		try {
			Document doc = Factory.Document.fetchInstance(os, "{" + bean.getDocumentId() + "}", pf );
			return doc;
		} catch (Exception e) {
			throw new CMException("Error fetching document", e);
		}
	}
	
	public List<File> getDocumentAttachment(Document doc) throws CMException {
		List<File> attachments = new ArrayList<File>();
		if (!doc.get_IsCurrentVersion()) {	
			bean.setStatus(DocumentBean.STATUS_IGNORE);
			Log.debug(Thread.currentThread().getName() + ": document " + bean.getDocumentId() + " has been ignored");
			return attachments;
		}

		// Get content elements and iterate list.
		ContentElementList docContentList = doc.get_ContentElements();
		Iterator iter = docContentList.iterator();
		while (iter.hasNext()) {
		    ContentTransfer ct = (ContentTransfer) iter.next();

		    // Get the content of the element.		    
		    byte[] buffer;
		    File file = new File(ct.get_RetrievalName());
		    try (InputStream is = ct.accessContentStream(); OutputStream os = new FileOutputStream(file)) {
		    	buffer = new byte[is.available()];
		    	int bytesRead;
		        while ((bytesRead = is.read(buffer)) != -1) {
		        	os.write(buffer, 0, bytesRead);
		        }
		    	attachments.add(file);
			} catch(IOException ioe) {
		        throw new CMException("Error getting attachment content", ioe);
		    }
		    break;
		}
		return attachments;
	}
	
	public void saveAttachmentAsPDF(File attachment) throws CMException {
		try (ByteArrayInputStream is = new ByteArrayInputStream(Files.readAllBytes(attachment.toPath()))){
			// Get document.
			Document doc = Factory.Document.fetchInstance(os, bean.getDocumentId(), null);
			
			if(!doc.get_IsReserved()){
				doc.checkout(ReservationType.OBJECT_STORE_DEFAULT, null, null, null);
				doc.save(RefreshMode.REFRESH);   
			}

			// Get the Reservation object from the Document object.
			Document reservation = (Document) doc.get_Reservation();

			// Create content element.
		    ContentTransfer ct = Factory.ContentTransfer.createInstance();
		    String mimeType = doc.get_MimeType();
		    if(mimeType.equalsIgnoreCase(PDF) || mimeType.contains(IMAGES)) {
		    	ct.set_ContentType("application/pdf");
		    }else {
		    	ct.set_ContentType(mimeType);
		    }
		    
			ct.setCaptureSource(is);
			ct.set_RetrievalName(attachment.getName());
			
			
			byte[] content = Files.readAllBytes(attachment.toPath());
			ContentTransfer cto = Factory.ContentTransfer.createInstance();
		    cto.set_ContentType(mimeType);
			cto.setCaptureSource(new ByteArrayInputStream(content));
			cto.set_RetrievalName(attachment.getName());
			
		    ContentElementList cel = Factory.ContentElement.createList();
		    cel.add(cto);
		    cel.add(ct);
		    
		    if (cel != null) {
		    	reservation.get_ContentElements().clear();
		    	reservation.get_ContentElements().addAll(cel);
			}

			// Check in Reservation object as major version.
			reservation.checkin(AutoClassify.DO_NOT_AUTO_CLASSIFY, CheckinType.MAJOR_VERSION);
			reservation.save(RefreshMode.REFRESH);
			bean.setNewDocumentId(reservation.get_Id().toString().replace("{","").replace("}", ""));
	    } catch (Exception e) {
	    	e.printStackTrace();
		    throw new CMException("Error saving file " + attachment.getName() + " to document " + bean.getDocumentId(), e);
		}
	}
	
	public void saveAttachmentAsText(StringBuilder attachment, File sourceFile,String mimeType) throws CMException {
		
		try (ByteArrayInputStream is = new ByteArrayInputStream(attachment.toString().getBytes("UTF-8"))){
			
			Document doc = Factory.Document.fetchInstance(os, bean.getDocumentId(), null);
			if(!doc.get_IsReserved()){
				doc.checkout(ReservationType.OBJECT_STORE_DEFAULT, null, null, null);
				doc.save(RefreshMode.REFRESH);   
			}

			// Get the Reservation object from the Document object.
			Document reservation = (Document) doc.get_Reservation();
			
			// Create content element.
		    ContentTransfer ct = Factory.ContentTransfer.createInstance();
		    ct.set_ContentType("text/plain");
			ct.setCaptureSource(is);
			ct.set_RetrievalName(sourceFile.getName()+".txt");
			
			byte[] content = Files.readAllBytes(sourceFile.toPath());
			ContentTransfer cto = Factory.ContentTransfer.createInstance();
		    cto.set_ContentType(mimeType);
			cto.setCaptureSource(new ByteArrayInputStream(content));
			cto.set_RetrievalName(sourceFile.getName());
			
		    ContentElementList cel = Factory.ContentElement.createList();
		    cel.add(cto);
		    cel.add(ct);
		    
		    if (cel != null) {
		    	reservation.get_ContentElements().clear();
		    	reservation.get_ContentElements().addAll(cel);
			}

			// Check in Reservation object as major version.
			reservation.checkin(AutoClassify.DO_NOT_AUTO_CLASSIFY, CheckinType.MAJOR_VERSION);
			reservation.save(RefreshMode.REFRESH);
			bean.setNewDocumentId(reservation.get_Id().toString().replace("{","").replace("}", ""));
	    } catch (Exception e) {
		    throw new CMException("Error saving file " + sourceFile.getName() + " to document " + bean.getDocumentId(), e);
		}
	}

	public void closeCMConnection() throws Exception {
		UserContext.get().popSubject();
	}

}
