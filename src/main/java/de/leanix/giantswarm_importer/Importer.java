package de.leanix.giantswarm_importer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import net.leanix.api.ResourcesApi;
import net.leanix.api.ServicesApi;
import net.leanix.api.common.ApiClient;
import net.leanix.api.common.ApiException;
import net.leanix.api.models.FactSheetHasRequires;
import net.leanix.api.models.Resource;
import net.leanix.api.models.Service;
import net.leanix.api.models.ServiceHasResource;
import net.leanix.dropkit.apiclient.ApiClientBuilder;
import net.leanix.metrics.api.PointsApi;
import net.leanix.metrics.api.models.Field;
import net.leanix.metrics.api.models.Point;
import net.leanix.metrics.api.models.Tag;

/**
 * Hello world!
 *
 */
public class Importer {

	private static final String TOKEN_HOST = System.getenv("TOKEN_HOST");
	private static final String METRICS_API_BASE_PATH = System.getenv("METRICS_API_BASE_PATH");
	private static final String API_BASE_PATH = System.getenv("API_BASE_PATH");
	private static final String TOKEN = System.getenv("TOKEN");
	private static final String WORKSPACE = System.getenv("WORKSPACE");

	private ApiClient client;
	private ServicesApi servicesApi;
	private ResourcesApi resourcesApi;

	private net.leanix.dropkit.apiclient.ApiClient metricsClient;
	private PointsApi pointsApi;

	public static void main(String[] args) throws Exception {
		new Importer().doImport("swarm.json");
	}

	public Importer() {
		client = new net.leanix.api.common.ApiClientBuilder().withBasePath(API_BASE_PATH)
				.withTokenProviderHost(TOKEN_HOST).withPersonalAccessToken(TOKEN).withDebugging(true).build();

		metricsClient = new ApiClientBuilder().withBasePath(METRICS_API_BASE_PATH).withTokenProviderHost(TOKEN_HOST)
				.withPersonalAccessToken(TOKEN).withDebugging(true).build();

		servicesApi = new ServicesApi(client);
		resourcesApi = new ResourcesApi(client);
		pointsApi = new PointsApi(metricsClient);
	}

	public void doImport(String file) throws Exception {
		InputStream is = new FileInputStream(file);
		JSONObject json = new JSONObject(IOUtils.toString(is));

		Service service = createOrUpdateService(json);
		deleteAllServiceHasResources(service);
		createResources(json, service);
		createDeployment(service);
	}

	private void createDeployment(Service service) throws net.leanix.dropkit.apiclient.ApiException {

		Point point = new Point();
		point.setMeasurement("Deployments");
		point.setWorkspaceId(WORKSPACE);
		point.setTime(new Date());

		Field field = new Field();
		field.setK("deployments");
		field.setV(1.0);

		Tag tag = new Tag();
		tag.setK("application");
		tag.setV(service.getID());

		point.getTags().add(tag);
		point.getFields().add(field);
		pointsApi.createPoint(point);
	}

	private void deleteAllServiceHasResources(Service service) throws ApiException {
		for (ServiceHasResource serviceHasResource : servicesApi.getServiceHasResources(service.getID())) {
			servicesApi.deleteServiceHasResource(service.getID(), serviceHasResource.getID());
		}
	}

	private void createResources(JSONObject json, Service service) throws ApiException {
		JSONObject jsonObject = json.getJSONObject("components");
		for (String key : jsonObject.keySet()) {
			Resource createdResource = createOrUpdateResource(key, jsonObject.getJSONObject(key).toString(), "");

			ServiceHasResource serviceHasResource = new ServiceHasResource();
			serviceHasResource.setServiceID(service.getID());
			serviceHasResource.setResourceID(createdResource.getID());
			servicesApi.createServiceHasResource(service.getID(), serviceHasResource);
		}

		for (String key : jsonObject.keySet()) {
			JSONObject component = jsonObject.getJSONObject(key);
			Resource resource = getResource(key, "");

			if (component.has("links")) {
				JSONArray links = component.getJSONArray("links");

				for (Object object : links) {
					JSONObject link = (JSONObject) object;

					Resource requiredResource = getResource(link.getString("component"), "");

					FactSheetHasRequires factSheetHasRequires = new FactSheetHasRequires();
					factSheetHasRequires.setFactSheetID(resource.getID());
					factSheetHasRequires.setFactSheetRefID(requiredResource.getID());
					resourcesApi.createFactSheetHasRequires(resource.getID(), factSheetHasRequires);

				}
			}

			if (component.has("image")) {
				String image = component.getString("image");
				int index = image.indexOf(":");
				Resource requiredResource = createOrUpdateResource(image.substring(0, index), "",
						image.substring(index + 1, image.length()));
				requiredResource.getTags().add("image");
				resourcesApi.updateResource(requiredResource.getID(), requiredResource);

				FactSheetHasRequires factSheetHasRequires = new FactSheetHasRequires();
				factSheetHasRequires.setFactSheetID(resource.getID());
				factSheetHasRequires.setFactSheetRefID(requiredResource.getID());
				resourcesApi.createFactSheetHasRequires(resource.getID(), factSheetHasRequires);

			}
		}

	}

	private Resource createOrUpdateResource(String name, String description, String release) throws ApiException {
		Resource resource = getResource(name, release);
		if (resource == null) {
			resource = new Resource();
			setResourceParams(name, description, release, resource);
			resource = resourcesApi.createResource(resource);
		} else {
			setResourceParams(name, description, release, resource);
			resource = resourcesApi.updateResource(resource.getID(), resource);

			for (FactSheetHasRequires factSheetHasRequires : resourcesApi
					.getFactSheetHasRequiresAll(resource.getID())) {
				resourcesApi.deleteFactSheetHasRequires(resource.getID(), factSheetHasRequires.getID());
			}

		}

		return resource;
	}

	private void setResourceParams(String name, String description, String release, Resource resource) {
		resource.setName(name);
		resource.setDescription(description);
		resource.setResourceType("SOFTWARE");
		resource.setRelease(release);
	}

	private Resource getResource(String name, String release) throws ApiException {
		Resource resource = null;
		for (Resource existingResource : resourcesApi.getResources(true, name)) {
			if (name.equals(existingResource.getName()) && release.equals(existingResource.getRelease())) {
				resource = existingResource;
				break;
			}
		}
		return resource;
	}

	private Service createOrUpdateService(JSONObject json) throws ApiException {
		String name = json.getString("name");
		List<Service> services = servicesApi.getServices(true, name);

		Service service = null;
		for (Service existingService : services) {
			if (name.equals(existingService.getName())) {
				service = existingService;
				break;
			}
		}

		if (service == null) {
			service = new Service();
			service.setName(name);
			service.setDescription(json.toString());
			service = servicesApi.createService(service);
		} else {
			service.setName(name);
			service.setDescription(json.toString());
			service = servicesApi.updateService(service.getID(), service);
		}

		return service;
	}
}
