package com.eulerity.hackathon.imagefinder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;


@WebServlet(
    name = "ImageFinder",
    urlPatterns = {"/main"}
)
public class ImageFinder extends HttpServlet{

	private static final int SUBPAGE_THREAD_POOL_SIZE = 100;
	private static final int THREAD_POOL_SIZE = 50;
	private static final long serialVersionUID = 1L;

	protected static final Gson GSON = new GsonBuilder().create();


	//This is the main crawlAndExtractImages Function which calls the inner craw function recursively until the depth = 0
	private List<String> crawlAndExtractImages(String url, int depth) throws IOException, InterruptedException {
		// Set to store unique image URLs (synchronized for thread safety)
		Set<String> imageUrls = Collections.synchronizedSet(new HashSet<>());

		//Start crawling recursively
		crawl(url, depth, imageUrls);

		// Convert set to list for the final result and return the list
        return new ArrayList<>(imageUrls);
	}

	// Recursive function to crawl the given URL and extract images
	private void crawl(String url, int depth, Set<String> imageUrls) {

		// Base case: Stop crawling when depth becomes zero
		if (depth <= 0) {
			return;
		}

		try {
			// Connect to the URL and retrieve the document
			Connection connection = Jsoup.connect(url).ignoreContentType(true).timeout(5000);  // Seting timeout to 5sec (skip if it take more time)
			Document document = connection.get();

			//Continue only if the status code is 200
			if (connection.response().statusCode() == 200) {
				// Start image extraction for the current page
				CompletableFuture<Void> imageExtractionFuture = extractImagesAsync(document, imageUrls);

				// Extract all the links available on the current page
				Elements links = document.select("a[href]");

				// Create a thread pool for sub-page crawling
				ExecutorService subPageExecutor = Executors.newFixedThreadPool(SUBPAGE_THREAD_POOL_SIZE);

				// Create futures for all sub-pages and wait for their completion
                CompletableFuture<Void> allSubPages = CompletableFuture.allOf(links.stream()
                        .map(link -> link.absUrl("href"))
                        .map(subPageUrl -> CompletableFuture.runAsync(() -> crawl(subPageUrl, depth - 1, imageUrls), subPageExecutor)).toArray(CompletableFuture[]::new));

				// Shutdown the thread pool and wait for termination
				subPageExecutor.shutdown();
				subPageExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

				// Wait for sub-pages and image extraction to complete
				allSubPages.thenComposeAsync(v -> imageExtractionFuture).join();
			} else {
				System.out.println("HTTP error fetching URL. Status=" + connection.response().statusCode() + ", URL=" + url);
			}
		} catch (IOException | IllegalArgumentException | InterruptedException e) {
			// Handle invalid URLs (e.g., log or print the error)
			System.out.println("Invalid URL: " + url);
		}
	}


	// Asynchronous function to extract images from the document
	private CompletableFuture<Void> extractImagesAsync(Document document, Set<String> imageUrls) {
		// Select all image elements from the document
		Elements imgElements = document.select("img");

		// Create a thread pool for image extraction
		ExecutorService imageExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

		// Create futures for all images and wait for their completion
        CompletableFuture<Void> allImages = CompletableFuture.allOf(imgElements.stream()
                .map(imgElement -> CompletableFuture.runAsync(() -> {
                    String imageUrl = imgElement.absUrl("src");
                    if (!imageUrl.isEmpty() && isValidImage(imageUrl)) {
                        imageUrls.add(imageUrl);
                    }
                }, imageExecutor)).toArray(CompletableFuture[]::new));

		// Shutdown the thread pool
		imageExecutor.shutdown();

		return allImages;
	}


	//Function to check the validity of url for image extraction
	private boolean isValidImage(String url) {
		try {
			URI tempUri = new URI(url);
			URL tempUrl = tempUri.toURL();

			// Check if the URL points to a valid image file
			HttpURLConnection connection = (HttpURLConnection) tempUrl.openConnection();
			connection.setRequestMethod("HEAD");  // Use HEAD method to retrieve headers only
			int statusCode = connection.getResponseCode();

			// Check if the URL is not empty, return status code 200 and has a valid image extention present in it.
			return !url.isEmpty() && statusCode == 200 && (url.contains("jpg") || url.contains("jpeg") || url.contains("png") || url.contains("svg") || url.contains("gif"));
		} catch (URISyntaxException | IOException e) {
			System.out.println("Error checking image validity for URL: " + url);
			return false;
		}
	}

	private boolean isSameDomain(String baseDomain, String url)
	{
		try{
			URI baseUri = new URI(baseDomain);
			URI uri = new URI(url);
			System.out.println("Base URI"+baseDomain+" and gethost"+baseUri.getHost());
			System.out.println("String uri"+url+" and gethost"+uri.getHost());
			return baseUri.getHost().equalsIgnoreCase(uri.getHost());
		}catch (URISyntaxException e)
		{
			return false;
		}
	}

	@Override
	protected final void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("text/json");
		String path = req.getServletPath();
		String url = req.getParameter("url");

		//Get the depth for crawling
		int depth = Integer.parseInt(req.getParameter("depth"));
		System.out.println("Got request of:" + path + " with query param:" + url);

		//Store all the image url Stings in a List by calling the crawling function here
		List<String> crawledImages = null;
		try {
			crawledImages = crawlAndExtractImages(url,depth);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		//Send the result List of images as Json to the frontend
		resp.getWriter().print(GSON.toJson(crawledImages));
	}

}
