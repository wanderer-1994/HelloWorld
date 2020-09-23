const express = require("express");
const app = express();
app.get("/status", (req, res) => {
    res.send("Hello from Nodejs http server!")
})

app.listen(5000, () => {
    console.log("Nodejs http server listening port 5000!")
})