package org.mskcc.cbio.portal.servlet;

import org.mskcc.cbio.portal.dao.*;
import org.mskcc.cbio.portal.model.*;
import org.mskcc.cbio.portal.util.AccessControl;
import org.mskcc.cbio.portal.web_api.ConnectionManager;
import org.mskcc.cbio.portal.web_api.ProtocolException;
import org.mskcc.cbio.portal.util.*;

import org.apache.log4j.Logger;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringUtils;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import javax.servlet.*;
import javax.servlet.http.*;

/**
 *
 * @author jj
 */
public class PatientView extends HttpServlet {
    private static Logger logger = Logger.getLogger(PatientView.class);
    public static final String ERROR = "user_error_message";
    public static final String VIEW_TYPE = "view_type";
    public static final String SAMPLE_ID = "sample_id";
    public static final String PATIENT_ID = "case_id";
    public static final String PATIENT_ID_ATTR_NAME = "PATIENT_ID";
    public static final String PATIENT_CASE_OBJ = "case_obj";
    public static final String CANCER_STUDY = "cancer_study";
    public static final String HAS_SEGMENT_DATA = "has_segment_data";
    public static final String HAS_ALLELE_FREQUENCY_DATA = "has_allele_frequency_data";
    public static final String MUTATION_PROFILE = "mutation_profile";
    public static final String CANCER_STUDY_META_DATA_KEY_STRING = "cancer_study_meta_data";
    public static final String CNA_PROFILE = "cna_profile";
    public static final String MRNA_PROFILE = "mrna_profile";
    public static final String NUM_CASES_IN_SAME_STUDY = "num_cases";
    public static final String NUM_CASES_IN_SAME_MUTATION_PROFILE = "num_cases_mut";
    public static final String NUM_CASES_IN_SAME_CNA_PROFILE = "num_cases_cna";
    public static final String NUM_CASES_IN_SAME_MRNA_PROFILE = "num_cases_mrna";
    public static final String PATIENT_INFO = "patient_info";
    public static final String DISEASE_INFO = "disease_info";
    public static final String PATIENT_STATUS = "patient_status";
    public static final String CLINICAL_DATA = "clinical_data";
    public static final String TISSUE_IMAGES = "tissue_images";
    public static final String PATH_REPORT_URL = "path_report_url";
    
    public static final String DRUG_TYPE = "drug_type";
    public static final String DRUG_TYPE_CANCER_DRUG = "cancer_drug";
    public static final String DRUG_TYPE_FDA_ONLY = "fda_approved";
    
    // class which process access control to cancer studies
    private AccessControl accessControl;

    /**
     * Initializes the servlet.
     *
     * @throws ServletException Serlvet Init Error.
     */
    @Override
    public void init() throws ServletException {
        super.init();

	    ApplicationContext context =
			    new ClassPathXmlApplicationContext("classpath:applicationContext-security.xml");
	    accessControl = (AccessControl)context.getBean("accessControl");
    }
    
    /** 
     * Processes requests for both HTTP <code>GET</code> and <code>POST</code> methods.
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        XDebug xdebug = new XDebug( request );
        request.setAttribute(QueryBuilder.XDEBUG_OBJECT, xdebug);
        
        String cancerStudyId = request.getParameter(QueryBuilder.CANCER_STUDY_ID);
        request.setAttribute(QueryBuilder.CANCER_STUDY_ID, cancerStudyId);
        
        try {
            if (validate(request)) {
                setGeneticProfiles(request);
                setClinicalInfo(request);
                setNumCases(request);
                setCancerStudyMetaData(request);
            }
            
            if (request.getAttribute(ERROR)!=null) {
                String msg = (String)request.getAttribute(ERROR);
                xdebug.logMsg(this, msg);
                forwardToErrorPage(request, response, msg, xdebug);
            } else {
                RequestDispatcher dispatcher =
                        getServletContext().getRequestDispatcher("/WEB-INF/jsp/tumormap/patient_view/patient_view.jsp");
                dispatcher.forward(request, response);
            }
        
        } catch (DaoException e) {
            e.printStackTrace();
            xdebug.logMsg(this, "Got Database Exception:  " + e.getMessage());
            forwardToErrorPage(request, response,
                    "An error occurred while trying to connect to the database.", xdebug);
        } catch (ProtocolException e) {
            e.printStackTrace();
            xdebug.logMsg(this, "Got Protocol Exception " + e.getMessage());
            forwardToErrorPage(request, response,
                    "An error occurred while trying to authenticate.", xdebug);
        }
    }

    /**
     *
     * Tests whether there is allele frequency data for a patient in a cancer study.
     * It gets all the mutations and then checks the values for allele frequency.
     *
     * If mutationProfile is null then returns false
     *
     * @return Boolean
     *
     * @author Gideon Dresdner
     */
    public boolean hasAlleleFrequencyData(int sampleId, GeneticProfile mutationProfile) throws DaoException {

        if (mutationProfile == null) {
            // fail quietly
            return false;
        }

        return DaoMutation.hasAlleleFrequencyData(mutationProfile.getGeneticProfileId(), sampleId);
    }

    private boolean validate(HttpServletRequest request) throws DaoException {
        
        // by default; in case return false;
        request.setAttribute(HAS_SEGMENT_DATA, Boolean.FALSE);
        request.setAttribute(HAS_ALLELE_FREQUENCY_DATA, Boolean.FALSE);
        
        String sampleIdsStr = request.getParameter(SAMPLE_ID);
        String patientIdsStr = request.getParameter(PATIENT_ID);
        if ((sampleIdsStr == null || sampleIdsStr.isEmpty())
                && (patientIdsStr == null || patientIdsStr.isEmpty())) {
            request.setAttribute(ERROR, "Please specify at least one case ID or patient ID. ");
            return false;
        }
        
        String cancerStudyId = (String) request.getAttribute(QueryBuilder.CANCER_STUDY_ID);
        if (cancerStudyId==null) {
            request.setAttribute(ERROR, "Please specify cancer study ID. ");
            return false;
        }
        
        CancerStudy cancerStudy = DaoCancerStudy.getCancerStudyByStableId(cancerStudyId);
        if (cancerStudy==null) {
            request.setAttribute(ERROR, "We have no information about cancer study "+cancerStudyId);
            return false;
        }

        Set<Sample> samples = new HashSet<Sample>();
        Set<String> sampleIds = new HashSet<String>();
        if (sampleIdsStr!=null) {
            for (String sampleId : sampleIdsStr.split(" +")) {
                Sample _sample = DaoSample.getSampleByCancerStudyAndSampleId(cancerStudy.getInternalId(), sampleId);
                if (_sample != null) {
                    samples.add(_sample);
                    sampleIds.add(_sample.getStableId());
                }
            }
        }
        
        request.setAttribute(VIEW_TYPE, "sample");
        if (patientIdsStr!=null) {
            request.setAttribute(VIEW_TYPE, "patient");
            for (String patientId : patientIdsStr.split(" +")) {
                Patient patient = DaoPatient.getPatientByCancerStudyAndPatientId(cancerStudy.getInternalId(), patientId);
                if (patient != null) {
                    for (Sample sample : DaoSample.getSamplesByPatientId(patient.getInternalId())) {
                        if (sample != null) {
                            samples.add(sample);
                            sampleIds.add(sample.getStableId());
                        }
                    }
                }
            }
        }

        if (samples.isEmpty()) {
            request.setAttribute(ERROR, "We have no information about the patient.");
            return false;
        }
        
        request.setAttribute(SAMPLE_ID, sampleIds);
        request.setAttribute(QueryBuilder.HTML_TITLE, "Patient: "+StringUtils.join(sampleIds,","));
        
        String cancerStudyIdentifier = cancerStudy.getCancerStudyStableId();

        if (accessControl.isAccessibleCancerStudy(cancerStudyIdentifier).size() != 1) {
            request.setAttribute(ERROR,
                    "You are not authorized to view the cancer study with id: '" +
                    cancerStudyIdentifier + "'. ");
            return false;
        }
        
        request.setAttribute(PATIENT_CASE_OBJ, samples);
        request.setAttribute(CANCER_STUDY, cancerStudy);

        request.setAttribute(HAS_SEGMENT_DATA, DaoCopyNumberSegment
                .segmentDataExistForCancerStudy(cancerStudy.getInternalId()));
        String firstSampleId = sampleIds.iterator().next(); 
        Sample firstSample = DaoSample.getSampleByCancerStudyAndSampleId(cancerStudy.getInternalId(), firstSampleId);
        request.setAttribute(HAS_ALLELE_FREQUENCY_DATA, 
                hasAlleleFrequencyData(firstSample.getInternalId(), cancerStudy.getMutationProfile(firstSampleId)));
        
        return true;
    }
    
    private void setGeneticProfiles(HttpServletRequest request) throws DaoException {
        CancerStudy cancerStudy = (CancerStudy)request.getAttribute(CANCER_STUDY);
        GeneticProfile mutProfile = cancerStudy.getMutationProfile();
        if (mutProfile!=null) {
            request.setAttribute(MUTATION_PROFILE, mutProfile);
            request.setAttribute(NUM_CASES_IN_SAME_MUTATION_PROFILE, 
                    DaoSampleProfile.countSamplesInProfile(mutProfile.getGeneticProfileId()));
        }
        
        GeneticProfile cnaProfile = cancerStudy.getCopyNumberAlterationProfile(true);
        if (cnaProfile!=null) {
            request.setAttribute(CNA_PROFILE, cnaProfile);
            request.setAttribute(NUM_CASES_IN_SAME_CNA_PROFILE, 
                    DaoSampleProfile.countSamplesInProfile(cnaProfile.getGeneticProfileId()));
        }
        
        GeneticProfile mrnaProfile = cancerStudy.getMRnaZscoresProfile();
        if (mrnaProfile!=null) {
            request.setAttribute(MRNA_PROFILE, mrnaProfile);
            request.setAttribute(NUM_CASES_IN_SAME_MRNA_PROFILE, 
                    DaoSampleProfile.countSamplesInProfile(mrnaProfile.getGeneticProfileId()));
        }
    }

    private void setCancerStudyMetaData(HttpServletRequest request) throws DaoException, ProtocolException {
        request.setAttribute(CANCER_STUDY_META_DATA_KEY_STRING, DaoSampleProfile.metaData(accessControl.getCancerStudies()));
    }
    
    private void setNumCases(HttpServletRequest request) throws DaoException {
        CancerStudy cancerStudy = (CancerStudy)request.getAttribute(CANCER_STUDY);
        request.setAttribute(NUM_CASES_IN_SAME_STUDY,DaoPatient.getPatientsByCancerStudyId(cancerStudy.getInternalId()).size());
    }
    
    private void setClinicalInfo(HttpServletRequest request) throws DaoException {
        Set<String> samples = (Set<String>)request.getAttribute(SAMPLE_ID);
        
        CancerStudy cancerStudy = (CancerStudy)request.getAttribute(CANCER_STUDY);
        int cancerStudyId = cancerStudy.getInternalId();
        List<ClinicalData> cds = DaoClinicalData.getSampleAndPatientData(cancerStudyId, samples);
        Map<String,Map<String,String>> clinicalData = new LinkedHashMap<String,Map<String,String>>();
        for (ClinicalData cd : cds) {
            String caseId = cd.getStableId();
            String attrId = cd.getAttrId();
            String attrValue = cd.getAttrVal();
            Map<String,String> attrMap = clinicalData.get(caseId);
            if (attrMap==null) {
                attrMap = new HashMap<String,String>();
                clinicalData.put(caseId, attrMap);
            }
            attrMap.put(attrId, attrValue);
        }
        request.setAttribute(CLINICAL_DATA, clinicalData);
        
        String sampleId = samples.iterator().next();
        
        // other cases with the same patient id
        Patient patient = DaoPatient.getPatientById(DaoSample.getSampleByCancerStudyAndSampleId(cancerStudyId, sampleId).getInternalPatientId());
        String patientId = patient.getStableId();
        
        int numOfSamplesInPatient = DaoSample.getSamplesByPatientId(patient.getInternalId()).size();
        request.setAttribute("num_tumors", numOfSamplesInPatient);
        
        if (numOfSamplesInPatient>1) {
            request.setAttribute(PATIENT_ID_ATTR_NAME, patientId);
        }
        
        request.setAttribute("has_timeline_data", Boolean.FALSE);
        if (patientId!=null) {
            request.setAttribute("has_timeline_data", DaoClinicalEvent.timeEventsExistForPatient(patient.getInternalId()));
        }

        request.setAttribute(PATIENT_ID, patientId);
        
        // images
        String tisImageUrl = getTissueImageIframeUrl(cancerStudy.getCancerStudyStableId(), samples.size()>1?patientId:sampleId);
        if (tisImageUrl!=null) {
            request.setAttribute(TISSUE_IMAGES, tisImageUrl);
        }
        
        // path report
        String typeOfCancer = cancerStudy.getTypeOfCancerId();
        if (patientId!=null && patientId.startsWith("TCGA-")) {
            String pathReport = getTCGAPathReport(typeOfCancer, patientId);
            if (pathReport!=null) {
                request.setAttribute(PATH_REPORT_URL, pathReport);
            }
        }
    }
    
    private String getTissueImageIframeUrl(String cancerStudyId, String caseId) {
        if (!caseId.toUpperCase().startsWith("TCGA-")) {
            return null;
        }
        
        // test if images exist for the case
        String metaUrl = GlobalProperties.getDigitalSlideArchiveMetaUrl(caseId);
        
        HttpClient client = ConnectionManager.getHttpClient(5000);

        GetMethod method = new GetMethod(metaUrl);

        Pattern p = Pattern.compile("<data total_count='([0-9]+)'>");
        try {
            int statusCode = client.executeMethod(method);
            if (statusCode == HttpStatus.SC_OK) {
                BufferedReader bufReader = new BufferedReader(
                        new InputStreamReader(method.getResponseBodyAsStream()));
                for (String line=bufReader.readLine(); line!=null; line=bufReader.readLine()) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        int count = Integer.parseInt(m.group(1));
                        return count>0 ? GlobalProperties.getDigitalSlideArchiveIframeUrl(caseId) : null;
                    }
                }
                
            } else {
                //  Otherwise, throw HTTP Exception Object
                logger.error(statusCode + ": " + HttpStatus.getStatusText(statusCode)
                        + " Base URL:  " + metaUrl);
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage());
        } finally {
            //  Must release connection back to Apache Commons Connection Pool
            method.releaseConnection();
        }
        
        return null;
    }
    
    // Map<TypeOfCancer, Map<CaseId, List<ImageName>>>
    private static Map<String,Map<String,String>> pathologyReports
            = new HashMap<String,Map<String,String>>();
    static final Pattern tcgaPathReportDirLinePattern = Pattern.compile("<a href=[^>]+>([^/]+/)</a>");
    static final Pattern tcgaPathReportPdfLinePattern = Pattern.compile("<a href=[^>]+>([^/]+\\.pdf)</a>");
    static final Pattern tcgaPathReportPattern = Pattern.compile("^(TCGA-..-....).+");
    private synchronized String getTCGAPathReport(String typeOfCancer, String caseId) {
        Map<String,String> map = pathologyReports.get(typeOfCancer);
        if (map==null) {
            map = new HashMap<String,String>();
            
            String pathReportUrl = GlobalProperties.getTCGAPathReportUrl(typeOfCancer);
            if (pathReportUrl!=null) {
                List<String> pathReportDirs = extractLinksByPattern(pathReportUrl,tcgaPathReportDirLinePattern);
                for (String dir : pathReportDirs) {
                    String url = pathReportUrl+dir;
                    List<String> pathReports = extractLinksByPattern(url,tcgaPathReportPdfLinePattern);
                    for (String report : pathReports) {
                        Matcher m = tcgaPathReportPattern.matcher(report);
                        if (m.find()) {
                            if (m.groupCount()>0) {
                                String exist = map.put(m.group(1), url+report);
                                if (exist!=null) {
                                    String msg = "Multiple Pathology reports for "+m.group(1)+": \n\t"
                                            + exist + "\n\t" + url+report;
                                    System.err.println(url);
                                    logger.error(msg);
                                }
                            }
                        }
                    }
                }
            }
            
            pathologyReports.put(typeOfCancer, map);
        }
        
        return map.get(caseId);
    }
    
    private static List<String> extractLinksByPattern(String reportsUrl, Pattern p) {
        HttpClient client = ConnectionManager.getHttpClient(20000);
        GetMethod method = new GetMethod(reportsUrl);
        try {
            int statusCode = client.executeMethod(method);
            if (statusCode == HttpStatus.SC_OK) {
                BufferedReader bufReader = new BufferedReader(
                        new InputStreamReader(method.getResponseBodyAsStream()));
                List<String> dirs = new ArrayList<String>();
                for (String line=bufReader.readLine(); line!=null; line=bufReader.readLine()) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        if (m.groupCount()>0) {
                            dirs.add(m.group(1));
                        }
                    }
                }
                return dirs;
            } else {
                //  Otherwise, throw HTTP Exception Object
                logger.error(statusCode + ": " + HttpStatus.getStatusText(statusCode)
                        + " Base URL:  " + reportsUrl);
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage());
        } finally {
            //  Must release connection back to Apache Commons Connection Pool
            method.releaseConnection();
        }
        
        return Collections.emptyList();
    }
    
    private void forwardToErrorPage(HttpServletRequest request, HttpServletResponse response,
                                    String userMessage, XDebug xdebug)
            throws ServletException, IOException {
        request.setAttribute("xdebug_object", xdebug);
        request.setAttribute(QueryBuilder.USER_ERROR_MESSAGE, userMessage);
        RequestDispatcher dispatcher =
                getServletContext().getRequestDispatcher("/WEB-INF/jsp/error.jsp");
        dispatcher.forward(request, response);
    }
    
    // <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /** 
     * Handles the HTTP <code>GET</code> method.
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /** 
     * Handles the HTTP <code>POST</code> method.
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /** 
     * Returns a short description of the servlet.
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "Short description";
    }// </editor-fold>
}
