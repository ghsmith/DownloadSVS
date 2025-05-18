package downloadsvs;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

public class DownloadSVS {
   
    public static void main(String[] args) throws Exception {

        Properties prop = new Properties();
        InputStream stream = new FileInputStream(args[0]);
        prop.load(stream);        
        
        String url = prop.getProperty("url");
        String u = prop.getProperty("u");
        String p = prop.getProperty("p");
        
        CookieHandler.setDefault(new CookieManager());
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(CookieHandler.getDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        // LOGIN
        {
            HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(url + "/uniview/Logon.ashx"))
              .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(String.format("{\"Domain\":\"\",\"Password\":\"%s\",\"UserName\":\"%s\"}", p, u)))
              .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        String slideId = args[1];
        String accNo = slideId.replaceAll("^([^-]*)-([^-]*)-([A-Z][^-]*)-([^-]*)$", "$1-$2");
        
        boolean anon = args.length > 2 && "anon".equals(args[2]);
                
        // FIND DESIRED CASE
        String uniViewPatientIdentifier = null;
        String patientHistoryFetchToken = null;
        String uniViewHistoryItemIdentifier = null;
        String fetchToken = null;
        String studyDescription = null;
        {
            
            HttpRequest requestSearch = HttpRequest.newBuilder()
              .uri(URI.create(url + "/uniview/Search.ashx"))
              .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(String.format("searchServer=2&freeText=\"%s\"", accNo)))
              .build();
            HttpResponse<String> responseSearch = client.send(requestSearch, HttpResponse.BodyHandlers.ofString());

            for(Iterator iterPatient = (new JSONObject(responseSearch.body())).getJSONArray("Patients").iterator(); iterPatient.hasNext(); ) {

                JSONObject joPatient = (JSONObject)iterPatient.next();
                uniViewPatientIdentifier = joPatient.getString("UniViewPatientIdentifier");
                patientHistoryFetchToken = joPatient.getString("PatientHistoryFetchToken");

                HttpRequest requestHistory = HttpRequest.newBuilder()
                  .uri(URI.create(url + "/uniview/GetPatientHistory.ashx"))
                  .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                  .POST(HttpRequest.BodyPublishers.ofString(String.format("patientHistoryFetchToken=%s", URLEncoder.encode(patientHistoryFetchToken))))
                  .build();
                HttpResponse<String> responseHistory = client.send(requestHistory, HttpResponse.BodyHandlers.ofString());

                for(Iterator iterHistory = new JSONObject(responseHistory.body()).getJSONArray("History").iterator(); iterHistory.hasNext(); ) {
                    JSONObject joHistory = (JSONObject)iterHistory.next();
                    String accessionNumber = joHistory.getString("AccessionNumber");
                    if(accessionNumber.equals(accNo)) {
                        uniViewHistoryItemIdentifier = joHistory.getString("UniViewHistoryItemIdentifier");
                        fetchToken = joHistory.getJSONArray("RequestedProcedures").getJSONObject(0).getString("FetchToken");
                        studyDescription = joHistory.getJSONArray("RequestedProcedures").getJSONObject(0).getString("StudyDescription");
                        break;
                    }
                }

                if(uniViewHistoryItemIdentifier != null) {
                    break;
                }

            }

        }

        // INITIALIZE PATHOLOGY SESSION
        String value = null;
        String hash = null;
        {
            HttpRequest requestPathSession = HttpRequest.newBuilder()
              .uri(URI.create(url + "/uniview/InitializePathologySession.ashx"))
              .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(String.format("fetchToken=%s", URLEncoder.encode(fetchToken))))
              .build();
            HttpResponse<String> responsePathSession = client.send(requestPathSession, HttpResponse.BodyHandlers.ofString());
            //System.out.println(response.statusCode());
            //System.out.println(response.body());
            JSONObject joPathSession = new JSONObject(responsePathSession.body());
            value = joPathSession.getString("Value");
            hash = joPathSession.getString("Hash");
            //System.out.println(value);
            //System.out.println(hash);
        }

        // LOOP THROUGH SLIDES
        {
            HttpRequest requestSlides = HttpRequest.newBuilder()
              .uri(URI.create(String.format(url + "/SectraPathologyServer/api/requestslides?requestId=%s&hash=%s", URLEncoder.encode(value), URLEncoder.encode(hash))))
              .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
              .GET()
              .build();
            HttpResponse<String> responseSlides = client.send(requestSlides, HttpResponse.BodyHandlers.ofString());
            JSONObject joSlides = new JSONObject(responseSlides.body());
            for(Iterator iterSlides = joSlides.getJSONArray("slides").iterator(); iterSlides.hasNext(); ) {
                JSONObject slideJson = (JSONObject)iterSlides.next();
                if(slideJson.get("labSlideIdString").equals(slideId) && slideJson.getBoolean("hasImage")) {
                    HttpRequest requestDetails = HttpRequest.newBuilder()
                      .uri(URI.create(String.format(url + "/SectraPathologyServer/api/slides/%s/details", slideJson.getString("id"))))
                      .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                      .GET()
                      .build();
                    HttpResponse<String> responseDetails = client.send(requestDetails, HttpResponse.BodyHandlers.ofString());
                    JSONObject joDetails = new JSONObject(responseDetails.body());
                    System.out.println();
                    System.out.println(String.format("%s\t%s\t%s\t%s",
                        slideJson.getString("requestIdString"),
                        slideJson.getString("labSlideIdString"),
                        slideJson.getString("staining"),
                        joDetails.getString("scanDateTime")
                    ));
                    System.out.println(String.format(url + "/SectraPathologyServer/slides/%s/files?requestId=%s&anonymize=%s", slideJson.getString("id"), accNo, anon ? "true" : "false"));
                    System.out.print(String.format("%-25s", "downloading ZIP..."));
                    HttpRequest requestDownload = HttpRequest.newBuilder()
                      .uri(URI.create(String.format(url + "/SectraPathologyServer/slides/%s/files?requestId=%s&anonymize=%s", slideJson.getString("id"), accNo, anon ? "true" : "false")))
                      .build();
                    HttpResponse<Path> responseDownload = client.send(requestDownload, HttpResponse.BodyHandlers.ofFileDownload(
                        Path.of("."), 
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE
                    ));
                    System.out.println(String.format("downloaded to: %s", responseDownload.body().getFileName()));
                    ZipFile zipFile = new ZipFile(responseDownload.body().toFile());
                    for(Iterator iterZipEntries = zipFile.entries().asIterator(); iterZipEntries.hasNext(); ) {
                        ZipEntry zipEntry = (ZipEntry)iterZipEntries.next();
                        if(zipEntry.getName().endsWith(".svs")) {
                            System.out.print(String.format("%-25s", "unzipping..."));
                            Files.copy(zipFile.getInputStream(zipEntry), new File(slideJson.getString("labSlideIdString") + "_" + slideJson.getInt("persistentId") + (anon ? "_anon" : "") + ".svs").toPath(), StandardCopyOption.REPLACE_EXISTING);
                            System.out.println(String.format("unzipped to: %s", slideJson.getString("labSlideIdString") + "_" + slideJson.getInt("persistentId") + (anon ? "_anon" : "") + ".svs"));
                        }
                    }
                    zipFile.close();
                    System.out.print(String.format("%-25s", "deleting ZIP..."));
                    responseDownload.body().toFile().delete();
                    System.out.println("ZIP deleted");
                }
            }
        }

        // LOGOUT
        {
            HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(url + "/uniview/Logout.ashx"))
              .setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(String.format("0")))
              .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        
    }
    
}
