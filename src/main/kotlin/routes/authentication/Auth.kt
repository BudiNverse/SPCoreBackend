package routes.authentication

import com.github.kittinunf.fuel.httpPost
import database.AuthSource
import exceptions.*
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.*
import io.ktor.util.ValuesMap
import models.StringResponse
import models.User

fun Route.auth(path: String) = route("$path/auth") {
    post("/login") {
        val form = call.receive<ValuesMap>()
        val isAuth = validateWithSp(form)
        val adminNo = form["adminNo"].toString()
        val firebaseToken = form["firebaseRegistrationToken"]



        when (isAuth) {
            2 -> call.respond(HttpStatusCode.Unauthorized, ErrorMsg("Locked out due to too many attempts",
                    LOCKED_OUT_BY_SP))
            3 -> call.respond(HttpStatusCode.Unauthorized, ErrorMsg("Wrong Spice Credentials",
                    WRONG_SPICE_CRENDENTIALS))
            else -> {
                val isUserExist = AuthSource().isUserExist(form["adminNo"].toString())
                val src = AuthSource()
                if (!isUserExist) {
                    // if user is not in table, insert user into table
                    val hasRegistered = src.registerUser(
                            User(adminNo, null, null))
                    // if insertion is ok -> issue JWT
                    if (hasRegistered == 1) {
                        val user= src.getUserById(adminNo)
                        val insertIntoUserDeviceResult = if(user != null) firebaseToken?.let { it1 -> src.insertDeviceId(user.adminNo, it1) } else 0
                        if(insertIntoUserDeviceResult != 0){
                            val jwt = user?.let(JwtConfig::makeToken)
                            if (jwt != null)
                                call.respond(JwtObjForFrontEnd(jwt,user.userName,user.displayName))
                        }else{
                            call.respond(HttpStatusCode.Unauthorized, ErrorMsg("firebaseRegistrationToken cannot be empty/null!", BAD_REQUEST))
                        }
                    }

                } else {
                    val user= src.getUserById(adminNo)
                    val insertIntoUserDeviceResult = if(user != null) firebaseToken?.let { it1 -> src.insertDeviceId(user.adminNo, it1) } else 0
                    if(insertIntoUserDeviceResult != 0){
                        val jwt = user?.let(JwtConfig::makeToken)
                        if (jwt != null)
                            call.respond(JwtObjForFrontEnd(jwt,user.userName,user.displayName))
                    }else{
                        call.respond(HttpStatusCode.Unauthorized, ErrorMsg("firebaseRegistrationToken cannot be empty/null!", BAD_REQUEST))
                    }
                }
            }
        }

    }

    put("/updateUser") {
        val user = requireLogin()
        val form = call.receive<ValuesMap>()
        when (user) {
            null -> call.respond(HttpStatusCode.Unauthorized, ErrorMsg("Missing JWT", MISSING_JWT))
            else -> {
                try {
                    val hasUpdated = AuthSource().updateUser(
                            User(user.adminNo,
                                    form["username"].toString(),
                                    form["displayName"].toString()
                            ))
                    if (hasUpdated == 1)
                        call.respond(StringResponse("${user.adminNo} has been updated!"))

                } catch (e: DuplicateFound) {
                    call.respond(HttpStatusCode.BadRequest, ErrorMsg("Username already taken!", DUPLICATE_FOUND))
                }
            }
        }
    }

}

/**
 * Perform a HTTP POST to SP's server
 * @param form
 * @return Boolean isAuth
 * @throws
 */
fun validateWithSp(form: ValuesMap): Int {
    val url = "https://sso.sp.edu.sg/pkmslogin.form"
    var isAuth = 0
    val username = form["adminNo"].toString()
    val password = form["password"].toString()

    val (request, response, result) = url.httpPost(listOf(
            "username" to username,
            "password" to password,
            "login-form-type" to "pwd"
    )).responseString()

    isAuth = when {
        response.toString().contains("locked out") -> 2
        response.toString().contains("function get(name, url){\n" +
                "   if(name=(new RegExp('[?&]'+encodeURIComponent(name)+'=([^&]*)')).exec(url))\n" +
                "      return decodeURIComponent(name[1]);\n" +
                "}") -> 1
        else -> 3
    }

    return isAuth
}
