/**
 * Executes the given HTTP request synchronously using the provided Request and returns
 * the ContentResponse after updating session state and checking the response.
 *
 * @param request   the HTTP request to execute
 * @param operation a label for logging or error messages
 * @return the ContentResponse from the HTTP request
 * @throws HoneywellTCCException if the request fails or returns an unexpected response
 */
private ContentResponse executeRequest(Request request, String operation) throws HoneywellTCCException {
    try {
        ContentResponse response = request.send();
        updateSessionState(response);
        checkStatusCodes(response);
        return response;
    } catch (Exception e) {
        handleException(operation, e);
        // This line will never be reached since handleException always throws.
        throw new HoneywellTCCException("Unreachable", e);
    }
} 