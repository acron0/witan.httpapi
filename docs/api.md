# Witan HTTP API Documentation

## How to inspect all the API endpoints with Swagger.

If you want a visual overview of the complete API reference then the Witan API is availble with a Swagger reference implementation. This will give you the opportunity to test out elements of the API without any programming. Swagger is interactive, meaning that you can test out API calls by filling in the fields provided. You will be required to login with your credentials before use as the authentication token is required in the other API calls.


### Example: Looking at the Swagger docs for the API.

```
https://api.witanforcities.com
```

When you visit the URL you will see the following screen. 

![api-screenshot.png](api-screenshot.png)

Clicking on the relevant API routes will expand into an information window where see the required fields and try out the API.

## How to check the API is healthy.

The healthcheck is a quick way to find out if the API endpoint is alive. If successful it will return `hello`.

### Example: Checking if the API is healthy.
```
curl -vvv -XGET https://api.witanforcities.com/healthcheck
```

### Example response: 

`hello`


## How to authenticate a user.

All calls to the API require authorisation based on your Witan login. You will receive an `auth-token` that will be used against subsequent API calls and a refresh token.

If the authorisation token is unused for longer than 30 minutes you will be required to refresh your token using the `/api/refresh` endpoint. For those interested in building a user interface that uses the Witan API then using the refresh token is the best way to keep your auth token alive. For those who are using code to call the API then it's assumed that you'll be using the login then use the auth token in subsequent calls.

### Example: User login to get authentication token

Using JSON the API expects the following payload. 

```
{"username":"yourloginemail@domain.com",
 "password":"your_password"}
```

The `curl` example below shows the payload being sent to the API.

```
curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' -d '{"username":"yourloginemail@domain.com","password":"your_password"}' 'https://api.witanforcities.com/api/login'
```

### Example response

```
{"token-pair":
   {"auth-token":"eyJh..........VdbpA",
    "refresh-token":"eyJhb........sr4A"
   }
}
```

## How the receipt mechanism works.

The Witan API is based on an asynchronus system. For aspects like uploads for example the API will issue you a receipt id. You are expected to use this receipt along with your auth token to query the API until the action is complete.

For example: you want to upload a file, once you've authenticated your user and have an auth token you will then call `/api/files/upload` with your auth token as a header. In response the API will send back a `receipt-id` value and a 202 response code. 

While Witan processes your file upload request you are expected to use the endpoint `/api/receipts/[receipt-id]` to see if your upload request url is ready. If Witan has successfully processed created an upload link you will receive a 200 response code along with upload link and filestore id. 

Once you have a 200 response and the required url/id combination you can safely upload your file. 

## How to upload a file.

The file upload aspect of the Witan API is not uploading files in the traditional sense. The API provides a mechanism to generate a URL and file id to upload a file to. The actual uploading of the file, and how that is executed, is up to you. 

There are three main steps to uploading a file and its metadata to Witan via the API.

### Step 1 - Generate your receipt-id.

Assuming that you have logged in and have an auth token the first step is to do a POST to the `/api/file/upload` endpoint. 

```
curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' --header 'authorization: eyJhb.....7SeMQ' 'https://api.witanforcities.com/api/files/upload'
```

This will return a receipt-id, using this id you can now poll against the API to get the file upload link.

```
curl -X GET --header 'Accept: application/json' --header 'authorization: eyJhb.....7SeMQ' 'https://api.witanforcities.com/api/receipts/abcdefg-abcd-abcd-abcd-abcdeghij'
```

### Step 2 - Get the upload link.

The response from the `/api/receipt/[receipt-id]` call will respond with either a 202 code, meaning the process is still being handled by Witan, you should continue to call the receipt endpoint with the same URL to see if the receipt has updated. 

If you receive a response with a 200 status code then the request is classed as complete and you will see the following response from the API. 

```
{
  "witan.httpapi.spec/uri": "https://prod-witan-kixi-datastore-filestore...........",
  "kixi.datastore.filestore/id": "d1bec41e.....46c2"
}
```

It is your responsibility to upload the file you want stored on Witan using the URL that is in the `witan.httpapi.spec/url` response field. 

```
curl -vvv -XPUT --data-binary /Users/jasonbell/Downloads/resp_gas.xml "https://prod-witan-kixi-datastore-filestore.s3-eu-west-1.amazonaws.com/d1bec41e-086e-4ad7-
```

### Step 3 - Post the metadata about that file.

Now that the file is uploaded to Witan you are required to send metadata about that file. If you do not do this then your file entry will not show in Witan searches or file lists. 

The minimum information for file metadata is:

```
{"kixi.datastore.metadatastore/header":false, 
 "kixi.datastore.metadatastore/size-bytes":13856,
 "kixi.datastore.metadatastore/name":"my_uploaded_file.xml",
 "kixi.datastore.metadatastore/file-type":"XML"}
```

#### Programatically finding the size of your file.

You are required to know the size of your file in bytes, for example if you were using the statistical language R for example you would use the `file.size()` command:

```
> file.size("/path/to/uploaded/file/your_file.xml")
[1] 13856
``` 
In Python:

```
>>> import os
>>> filesize = os.path.getsize("/path/to/uploaded/file/your_file.xml")
>>> filesize
13856
```

In PHP:

```
<?PHP
$filename = "/path/to/uploaded/file/your_file.xml";
var $filesize = filesize($filename);
?>
```

In Java:

```
File file = new File("/path/to/uploaded/file/your_file.xml");
double filesize = file.length();
```

### Sending the metadata to Witan

Once you have your metadata json then do a PUT to `/api/files/[file-id]/metadata`

```
curl -X PUT --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --header 'authorization: eyJhb....FdB24Pg' \ 
  -d '{"kixi.datastore.metadatastore/header":false, \ 
       "kixi.datastore.metadatastore/size-bytes":13856, \ 
       "kixi.datastore.metadatastore/name":"my_uploaded_file.xml", \ 
       "kixi.datastore.metadatastore/file-type":"XML"}' \
'https://api.witanforcities.com/api/files/a16ca......1c34f/metadata'
```

You'll receive receipt id which you can query against the `/api/receipts/[receipt-id]` endpoint to see the status of metadata creation. 


## How to find your files.

The `/api/files` endpoint gives you a list of the files that available in your account. 

### Using the API to get a full list of your files.

With your auth token send a GET as illustrated below:

```
curl -X GET --header 'Accept: application/json' \
--header 'authorization: eyJhb.....QdDcQ' \
  'https://api.witanforcities.com/api/files
```

This will return JSON with two elements: the paging information and the detail of the files available.

### Using the count and index paging parameters

You can find the total number of files available to you in the paging response:

```
"paging": {
    "total": 15,
    "count": 4,
    "index": 5
  },

```

The `index` is the starting point of the file count. A list with ten files for example and an `index` of `1` would skip the first file and show the remaining nine.

The `count` tells the API how many files to show in the response. Note that a value of `0` will give no file information in the response regardless of the `index` value.

For example, a `count` of `5` you would send 

```
https://api.witanforcities.com/api/files?count=5&index=0
```

Your next call would be `index + count`, following on from the example above the endpoint would look like this:

```
https://api.witanforcities.com/api/files?count=5&index=5
```

And you would carry on until all the files were listed. If the `index` value is greater than the `total` then no file info will be returned. 

## How to download a file.


## How to share a file with another user.


# Known Issues