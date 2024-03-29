package launchit.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import launchit.Launchit;
import launchit.auth.error.YggdrasilError;
import launchit.auth.model.*;
import launchit.auth.profile.LauncherProfiles;
import launchit.events.AuthEvent;
import launchit.utils.UrlUtils;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Random;
import java.util.UUID;

public class SessionManager
{
    private boolean debug;
    private Launchit it;
    private LauncherProfiles launcherProfiles;

    public SessionManager(Launchit it, boolean debug) {
        this.it = it;
        this.debug = debug;
        loadLauncherProfiles();
    }

    public void loadLauncherProfiles() {
        launcherProfiles = new LauncherProfiles();
        if (getProfileFile().exists()) {
            try {
                launcherProfiles = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .create()
                    .fromJson(FileUtils.readFileToString(getProfileFile(), StandardCharsets.UTF_8), LauncherProfiles.class);
                if (launcherProfiles == null)
                    launcherProfiles = new LauncherProfiles();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void saveProfiles() {
        try {
            FileUtils.writeStringToFile(getProfileFile(),
                    new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .setPrettyPrinting()
                    .create()
                    .toJson(launcherProfiles),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void doLogin(String login, String password, boolean crack)
    {
        it.getExecutorService().execute(() -> {
            try {
                if (!crack) {
                    AuthenticateRes res = authenticate(
                            Agent.getMinecraftAgent(),
                            login,
                            password,
                            getClientToken()
                    );
                    res.getSelectedProfile().setAccessToken(res.getAccessToken());
                    launcherProfiles.setClientToken(res.getClientToken());
                    launcherProfiles.setSelectedProfile(res.getSelectedProfile().getId());
                    launcherProfiles.getProfiles().put(res.getSelectedProfile().getId(), res.getSelectedProfile());
                    saveProfiles();
                    it.getEventBus().post(new AuthEvent.Login(null, res.getSelectedProfile()));
                } else {
                    String userID = UUID.randomUUID().toString().replace("-", "");
                    Profile profile = new Profile();
                    profile.setId(userID);
                    profile.setName(login);
                    profile.setCrack(true);
                    launcherProfiles.setSelectedProfile(login);
                    launcherProfiles.getProfiles().put(login, profile);
                    saveProfiles();
                    it.getEventBus().post(new AuthEvent.Login(null, profile));
                }

            } catch (IOException e) {
                e.printStackTrace();
            } catch (YggdrasilError yggdrasilError) {
                it.getEventBus().post(new AuthEvent.Login(yggdrasilError, null));
                yggdrasilError.printStackTrace();
            }
        });
    }

    public void doRefresh(String profile)
    {
        it.getExecutorService().execute(() -> {
            try {
                Profile p = launcherProfiles.getProfiles().get(profile);
                if (p == null) {
                    it.getEventBus().post(new AuthEvent.Refresh(null, null)); //TODO better errors system
                    return;
                }

                if (p.isCrack()) {
                    it.getEventBus().post(new AuthEvent.Refresh(null, p));
                    saveProfiles();
                    return;
                }

                RefreshRes res = refresh(p.getAccessToken(), launcherProfiles.getClientToken());
                p.setAccessToken(res.getAccessToken());
                launcherProfiles.setSelectedProfile(profile);
                saveProfiles();
                it.getEventBus().post(new AuthEvent.Refresh(null, p));
            } catch (IOException e) {
                e.printStackTrace();
            } catch (YggdrasilError yggdrasilError) {
                it.getEventBus().post(new AuthEvent.Refresh(yggdrasilError, null));
                yggdrasilError.printStackTrace();
            }
        });
    }

    public void doLogout(String profile)
    {
        it.getExecutorService().execute(() -> { //TODO: Add invalidate support
            Profile p = launcherProfiles.getProfiles().get(launcherProfiles.getSelectedProfile());
            if (p == null) {
                it.getEventBus().post(new AuthEvent.Logout(null, null));
                return;
            }
            launcherProfiles.setSelectedProfile(null);
            launcherProfiles.getProfiles().remove(profile);
            it.getEventBus().post(new AuthEvent.Logout(null, p));
            saveProfiles();
        });
    }

    /**
     * Authenticate an user with his username and password and a previously acquired clientToken
     *
     * @return The appropriated response object
     * @throws IOException
     * @throws YggdrasilError
     */
    public AuthenticateRes authenticate(Agent agent, String username, String password, String clientToken) throws IOException, YggdrasilError {
        AuthenticateReq req = new AuthenticateReq();
        req.setAgent(agent);
        req.setUsername(username);
        req.setPassword(password);
        req.setClientToken(clientToken);

        return request(req, "authenticate", AuthenticateRes.class);
    }

    /**
     * Refresh an access token, provided access token gets invalidated and a new one is returned
     *
     * @return The appropriated response object
     * @throws IOException
     * @throws YggdrasilError
     */
    public RefreshRes refresh(String accessToken, String clientToken) throws IOException, YggdrasilError {
        RefreshReq req = new RefreshReq();
        req.setAccessToken(accessToken);
        req.setClientToken(clientToken);

        return request(req, "refresh", RefreshRes.class);
    }

    /**
     * Internal use only, build request to the yggdrasil authentication server
     *
     * @throws IOException
     * @throws YggdrasilError
     */
    private <T> T request(Object data, String route, Class<T> responseClass) throws IOException, YggdrasilError {
        long currentTime = System.currentTimeMillis();
        Gson gson = new Gson();

        String request = gson.toJson(data);

        if (debug)
            System.out.println(String.format("[%s] request:  %s", currentTime, request));


        URL url = UrlUtils.join(it.getConfig().getAuthServer(), route);
        byte[] postDataByte = request.getBytes();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Content-Length", Integer.toString(postDataByte.length));
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);

        DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
        wr.write(postDataByte);
        wr.flush();
        wr.close();

        boolean status = String.valueOf(connection.getResponseCode()).startsWith("2");

        BufferedReader br = new BufferedReader(new InputStreamReader(status ? connection.getInputStream() : connection.getErrorStream()));
        String response = br.readLine();

        if (debug)
            System.out.println(String.format("[%s] response: %s", currentTime, response));

        if (responseClass != null && (response == null || response.isEmpty()))
            throw new IOException("Empty response");

        if (status)
            return responseClass == null ? null : gson.fromJson(response, responseClass);
        else
            throw new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson(response, YggdrasilError.class);
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public File getProfileFile() {
        return new File(it.getConfig().getInstallFolder(), "launcher_profiles.json");
    }

    public static String getClientToken() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if ((ni.isUp()) && (ni.getHardwareAddress() != null) && (!ni.isLoopback()) && (!ni.isVirtual()) && (!ni.getDisplayName().toLowerCase().contains("tunnel"))) {
                    return UUID.nameUUIDFromBytes(ni.getHardwareAddress()).toString();
                }
            }
        }
        catch (SocketException ignored) {}

        byte[] b = new byte[20];
        new Random().nextBytes(b);
        return UUID.nameUUIDFromBytes(b).toString();
    }

    public LauncherProfiles getLauncherProfiles() {
        return launcherProfiles;
    }
}
