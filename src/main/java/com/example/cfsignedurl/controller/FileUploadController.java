package com.example.cfsignedurl.controller;

import com.example.cfsignedurl.service.AWSS3ServiceSDKVersionOne;
import com.example.cfsignedurl.service.AWSS3ServiceSDKVersionTwo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;

@RestController
public class FileUploadController {

    @Autowired
    private AWSS3ServiceSDKVersionOne awss3ServiceSDKVersionOne;

    @Autowired
    private AWSS3ServiceSDKVersionTwo awss3ServiceSDKVersionTwo;


    @RequestMapping(value = "/upload/{request-identifier}/sdk-v1/signed-url", method = RequestMethod.GET)
    public String getCloudFrontSignedURLViaSDKVersionOne(@PathVariable(value = "request-identifier") String requestIdentifier) throws IOException {
        return awss3ServiceSDKVersionOne.getCloudFrontSignedUrl(requestIdentifier);
    }

    @RequestMapping(value = "/upload/{request-identifier}/sdk-v2/signed-url", method = RequestMethod.GET)
    public String getCloudFrontSignedURLViaSDKVersionTwo(@PathVariable(value = "request-identifier") String requestIdentifier) throws IOException {
        return awss3ServiceSDKVersionTwo.getCloudFrontSignedUrl(requestIdentifier);
    }

}
