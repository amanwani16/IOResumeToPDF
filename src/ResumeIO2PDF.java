import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.geom.Rectangle2D;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

public class ResumeIO2PDF {
    // Constants and global variables
    private static final String RESUME_PAGE = "https://resume.io/r/%s";
    private static final String RESUME_META = "https://ssr.resume.tools/meta/ssid-%s?cache=%s";
    private static final String RESUME_IMG = "https://ssr.resume.tools/to-image/ssid-%s-%d.%s?cache=%s&size=%d";
    private static final String RESUME_EXT = "png";
    private static final int RESUME_SIZE = 1800;
    private static final int TIMEOUT = 60 * 1000;
    private static final SimpleDateFormat RFC3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final Pattern RE_SID = Pattern.compile("^[\\p{Alnum}]+$");
    private static final Pattern RE_ID = Pattern.compile("^\\d+$");
    private static final Pattern RE_URL = Pattern.compile("^https://resume[.]io/r/([\\p{Alnum}]+)");

    private static boolean verbose = false;

    private static String sid="123TesT";  //Add your SID here
    private static String resumeFileName = "CustomName"; //Add the name for the resume pdf file here

    public static void main(String[] args) {
        // Parse and validate command-line arguments

        // Get resume metadata
        try {
            verboseLog("SecureID: " + sid);
            MetaInfo meta = getMeta(sid);
            if (meta == null) {
                System.out.println("Failed to get resume metadata.");
                System.exit(1);
            }

            // Get resume images
            List<String> images = getResumeImages(sid, meta.pages.size());
            if (images == null) {
                System.out.println("Failed to get resume images.");
                System.exit(1);
            } else {
                // Generate PDF from resume images and metadata
                if (!generatePDF(resumeFileName+".pdf", meta, images)) {
                    System.out.println("Failed to generate PDF.");
                    System.exit(1);
                }

                // Clean up downloaded images
                cleanup(images);

                System.out.printf("Resume stored to %s%n", resumeFileName+".pdf");
            }
        } catch (Exception e){
            System.out.println(e);
        }
    }

        private static MetaInfo getMeta (String sid){
            String metaUrl = String.format(RESUME_META, sid, RFC3339.format(new Date()));
            String json = downloadAsString(metaUrl);
            if (json == null) {
                return null;
            }

            Gson gson = new Gson();
            return gson.fromJson(json, MetaInfo.class);
        }

        private static List<String> getResumeImages (String sid,int numPages){
            List<String> images = new ArrayList<>();
            for (int pageId = 1; pageId <= numPages; pageId++) {
                String pageFile = String.format("%s-%d.%s", sid, pageId, RESUME_EXT);
                File file = new File(pageFile);
                if (!file.exists()) {
                    verboseLog(String.format("Download image #%d/%d", pageId, numPages));
                    String imgURL = String.format(RESUME_IMG, sid, pageId, RESUME_EXT, RFC3339.format(new Date()), RESUME_SIZE);
                    if (!downloadToFile(imgURL, pageFile)) {
                        return null;
                    }
                }
                images.add(pageFile);
            }

            verboseLog("Total " + images.size() + " pages");

            return images;
        }

        private static boolean generatePDF (String pdfFileName, MetaInfo meta, List < String > images){
            try (PDDocument pdf = new PDDocument()) {
                for (int i = 0; i < meta.pages.size(); i++) {
                    MetaPageInfo pageInfo = meta.pages.get(i);
                    String imagePath = images.get(i);

                    PDRectangle pageSize = new PDRectangle((float) pageInfo.viewport.width, (float) pageInfo.viewport.height);
                    PDPage page = new PDPage(pageSize);
                    pdf.addPage(page);

                    PDImageXObject image = PDImageXObject.createFromFile(imagePath, pdf);
                    try (PDPageContentStream contentStream = new PDPageContentStream(pdf, page, AppendMode.APPEND, true, true)) {
                        contentStream.drawImage(image, 0, 0, pageSize.getWidth(), pageSize.getHeight());
                    }

                    // Add links
                    for (MetaLink link : pageInfo.links) {
                        float x = (float) link.left;
                        float y = pageSize.getHeight() - (float) link.top - (float) link.height;
                        float width = (float) link.width;
                        float height = (float) link.height;
                        page.getAnnotations().add(createLinkAnnotation(new Rectangle2D.Float(x, y, width, height), link.url));
                    }
                }

                pdf.save(pdfFileName);
            } catch (IOException e) {
                System.out.println(e);
                return false;
            }

            return true;
        }

        // Other helper methods like downloadAsString, downloadToFile, createLinkAnnotation, cleanup, verboseLog
        private static String downloadAsString (String url){
            try {
                URL urlObj = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(60000);
                connection.setReadTimeout(60000);

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return null;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            } catch (IOException e) {
                return null;
            }
        }

        private static boolean downloadToFile (String url, String filePath){
            try {
                URL urlObj = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(60000);
                connection.setReadTimeout(60000);

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return false;
                }

                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(filePath)) {

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
            } catch (IOException e) {
                return false;
            }
            return true;
        }

        private static PDAnnotationLink createLinkAnnotation (Rectangle2D.Float rect, String uri){
            PDAnnotationLink link = new PDAnnotationLink();

            PDRectangle position = new PDRectangle(rect.x, rect.y, rect.width, rect.height);
            link.setRectangle(position);

            PDActionURI action = new PDActionURI();
            action.setURI(uri);
            link.setAction(action);

            return link;
        }

        private static void cleanup (List < String > images) {
            for (String image : images) {
                File file = new File(image);
                if (file.exists()) {
                    if (!file.delete()) {
                        System.err.println("Error on remove `" + image + "'");
                    } else {
                        verboseLog("Image `" + image + "' successfully deleted.");
                    }
                }
            }
        }

        private static void verboseLog (String message){
            if (verbose) {
                System.out.println(message);
            }
        }
    }



